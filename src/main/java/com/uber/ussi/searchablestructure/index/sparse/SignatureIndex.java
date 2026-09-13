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
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, SparseKeyType.SIGNATURE);
  }

  /**
   * The keys are signatures rather than terms, but the signature generator and the comparator that
   * verifies the candidates both read a row as a sparse record, so a row still has to be one.
   */
  @Override
  protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    validateSparseRecordType(termsAndValues, source);
  }

  @Override
  protected double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
    return getSignatureComparator()
        .getMinPrefixSumForSignatures((int) Math.ceil(sparseKeysUniValue), minSimilarity);
  }

  @Override
  protected SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
      LongTermsAndValues indexedRecord) {
    long[] sparseKeys = getDistinctSortedSparseKeys(indexedRecord);
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

  /**
   * Returns the record's distinct signatures. A record's signatures routinely collide with each
   * other, so unlike terms they have to be deduplicated before they can serve as keys.
   */
  @Override
  protected long[] getSparseKeys(LongTermsAndValues indexedRecord) {
    if (indexedRecord.termsLength() == 0) {
      return new long[0];
    }
    return LongHashSet.from(
            getSignatureComparator().getSignatures(indexedRecord, Constants.NUM_SIGNATURES_PER_ID))
        .toArray();
  }

  private long[] getDistinctSortedSparseKeys(LongTermsAndValues indexedRecord) {
    long[] sparseKeys = getSparseKeys(indexedRecord);
    Arrays.sort(sparseKeys);
    return sparseKeys;
  }

  @Override
  protected float getValueAtSparseKey(LongTermsAndValues indexedRecord, long sparseKey) {
    return (float) getSignatureComparator().getSignatureUniTransformedValue();
  }
}
