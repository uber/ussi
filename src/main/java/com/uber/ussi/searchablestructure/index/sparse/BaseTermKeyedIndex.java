/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * Shared implementation for the sparse indexes whose inverted lists are keyed by the terms of the
 * record they index, as opposed to by a signature derived from it. Every sparse index is an
 * inverted index, so it is the source of the keys that separates them.
 *
 * <p>Subclasses differ in what they index, not in how they read it: the record reaching these
 * methods is always the indexed form returned by {@link #toIndexedRecord}, so its terms are sorted
 * and distinct and it carries one value per term, whatever type the caller's record had.
 */
abstract class BaseTermKeyedIndex extends BaseSparseIndex {

  BaseTermKeyedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      SparseKeyType sparseKeyType) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, sparseKeyType);
  }

  @Override
  protected final double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
    return comparator.getMinPrefixSumForTermsAndValues(sparseKeysUniValue, minSimilarity);
  }

  @Override
  protected final SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
      LongTermsAndValues indexedRecord) {
    int numTerms = indexedRecord.termsLength();
    SparseKeyAndUniTransformedValue[] sparseKeys = new SparseKeyAndUniTransformedValue[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      sparseKeys[i] =
          new SparseKeyAndUniTransformedValue(
              indexedRecord.getTerm(i),
              comparator.getUniTransformedValue(indexedRecord.getValue(i)));
    }
    return sparseKeys;
  }

  @Override
  protected final long[] getSparseKeys(LongTermsAndValues indexedRecord) {
    return indexedRecord.getTerms();
  }

  @Override
  protected final float getValueAtSparseKey(LongTermsAndValues indexedRecord, long sparseKey) {
    int termIndex = indexOfTerm(indexedRecord, sparseKey);
    if (termIndex < 0) {
      throw new IllegalArgumentException(
          String.format("Sparse key %s is absent from the supplied terms and values.", sparseKey));
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
