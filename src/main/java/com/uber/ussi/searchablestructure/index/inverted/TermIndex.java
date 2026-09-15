/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.index.IndexType;

/**
 * Inverted index keyed by the terms of the record itself rather than by a signature derived from
 * it.
 *
 * <p>The record type decides what those terms are: a sparse record's own terms and values, whose
 * conjunction is the similarity exactly; or a sequence's distinct elements and their counts, which
 * only bound it. See {@link RecordIndexingStrategy}.
 */
public final class TermIndex extends BaseInvertedIndex {

  public TermIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, IndexType.INVERTED_TERM);
  }

  @Override
  protected double getMaxPrefixSum(
      double keysUniValue, double recordUniValue, double minSimilarity) {
    return comparator.getMaxPrefixSumForTermsAndValues(keysUniValue, minSimilarity);
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
   * Searching in place rather than through {@code getTerms}, which copies the whole term array,
   * keeps a full traversal of a record's keys from being quadratic in its length.
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
