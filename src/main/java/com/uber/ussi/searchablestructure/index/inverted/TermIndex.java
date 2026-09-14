/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.index.IndexType;

/**
 * Inverted index whose lists are keyed by the terms of the record it indexes, as opposed to by a
 * signature derived from it. Every index in this family keeps inverted lists, so it is the source
 * of the keys that separates them.
 *
 * <p>The record type decides what those terms are: a sparse record's own terms, keyed with its own
 * values, so that the terms a query and a candidate share determine their similarity exactly; or
 * the distinct elements of a sequence, keyed with how often each occurs, which bound how far apart
 * two sequences can be without saying how far apart they are. Either way the record reaching these
 * methods is the indexed form, so its terms are sorted and distinct and it carries one value per
 * term whatever type the caller supplied; see {@link RecordIndexingStrategy}.
 */
public final class TermIndex extends BaseInvertedIndex {

  public TermIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, IndexType.INVERTED_TERM);
  }

  @Override
  protected double getMinPrefixSum(double keysUniValue, double minSimilarity) {
    return comparator.getMinPrefixSumForTermsAndValues(keysUniValue, minSimilarity);
  }

  @Override
  protected KeyAndUniTransformedValue[] getKeysAndUniTransformedValues(
      LongTermsAndValues indexedRecord) {
    int numTerms = indexedRecord.termsLength();
    KeyAndUniTransformedValue[] keys = new KeyAndUniTransformedValue[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      keys[i] =
          new KeyAndUniTransformedValue(
              indexedRecord.getTerm(i),
              comparator.getUniTransformedValue(indexedRecord.getValue(i)));
    }
    return keys;
  }

  @Override
  protected long[] getKeys(LongTermsAndValues indexedRecord) {
    return indexedRecord.getTerms();
  }

  @Override
  protected float getValueAtKey(LongTermsAndValues indexedRecord, long key) {
    int termIndex = indexOfTerm(indexedRecord, key);
    if (termIndex < 0) {
      throw new IllegalArgumentException(
          String.format("Key %s is absent from the supplied terms and values.", key));
    }
    return indexedRecord.getValue(termIndex);
  }

  /**
   * Returns where {@code term} sits among a record's terms, or a negative number if it is absent.
   *
   * <p>This searches the record in place rather than through {@code getTerms}, which hands out a
   * copy of the whole term array. Callers ask for one key at a time while walking every key a
   * record has, so copying per call would make a single record's traversal quadratic in its length.
   */
  private static int indexOfTerm(LongTermsAndValues indexedRecord, long term) {
    int low = 0;
    int high = indexedRecord.termsLength() - 1;
    while (low <= high) {
      int middle = (low + high) >>> 1;
      long middleTerm = indexedRecord.getTerm(middle);
      if (middleTerm < term) {
        low = middle + 1;
      } else if (middleTerm > term) {
        high = middle - 1;
      } else {
        return middle;
      }
    }
    return -1;
  }
}
