/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.Constants;
import java.util.Arrays;

/** Approximate inverted index whose sparse keys are similarity-preserving signatures. */
public final class SignatureIndex extends BaseSparseIndex {

  public SignatureIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(
        namespaceConfig,
        rowNumToTermsAndValuesMap,
        rowNumToMetaMap,
        /* requireSignatureSupport */ true);
  }

  @Override
  protected double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
    return getSignatureComparator()
        .getMinPrefixSumForSignatures((int) Math.ceil(sparseKeysUniValue), minSimilarity);
  }

  @Override
  protected SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
      LongTermsAndValues termsAndValues) {
    long[] sparseKeys = getDistinctSortedSparseKeys(termsAndValues);
    SparseKeyAndUniTransformedValue[] sparseKeysAndValues =
        new SparseKeyAndUniTransformedValue[sparseKeys.length];
    double signatureUniTransformedValue =
        getSignatureComparator().getSignatureUniTransformedValue();
    for (int i = 0; i < sparseKeys.length; ++i) {
      sparseKeysAndValues[i] =
          new SparseKeyAndUniTransformedValue(sparseKeys[i], signatureUniTransformedValue);
    }
    return sparseKeysAndValues;
  }

  @Override
  protected long[] getSparseKeys(LongTermsAndValues termsAndValues) {
    if (termsAndValues.termsLength() == 0) {
      return new long[0];
    }
    return getSignatureComparator().getSignatures(termsAndValues, Constants.NUM_SIGNATURES_PER_ID);
  }

  private long[] getDistinctSortedSparseKeys(LongTermsAndValues termsAndValues) {
    long[] sparseKeys = LongHashSet.from(getSparseKeys(termsAndValues)).toArray();
    Arrays.sort(sparseKeys);
    return sparseKeys;
  }
}
