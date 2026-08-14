/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/** Exact inverted index for sparse terms and values. */
public final class InvertedIndex extends BaseSparseIndex {

  public InvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
  }

  @Override
  protected double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
    return comparator.getMinPrefixSumForTermsAndValues(sparseKeysUniValue, minSimilarity);
  }

  @Override
  protected SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
      LongTermsAndValues termsAndValues) {
    int numTerms = termsAndValues.termsLength();
    SparseKeyAndUniTransformedValue[] sparseKeys = new SparseKeyAndUniTransformedValue[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      sparseKeys[i] =
          new SparseKeyAndUniTransformedValue(
              termsAndValues.getTerm(i),
              comparator.getUniTransformedValue(termsAndValues.getValue(i)));
    }
    return sparseKeys;
  }

  @Override
  protected long[] getSparseKeys(LongTermsAndValues termsAndValues) {
    return termsAndValues.getTerms();
  }
}
