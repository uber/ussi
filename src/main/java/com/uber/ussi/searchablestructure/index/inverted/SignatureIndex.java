/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.index.IndexType;
import java.util.Arrays;

/**
 * Approximate inverted index whose keys are similarity-preserving signatures rather than the terms
 * they were generated from, so sharing a key says nothing about the values behind it and every
 * candidate's similarity has to be verified through the comparator.
 */
public final class SignatureIndex extends BaseInvertedIndex {

  /** How many signatures stand in for one row, which fixes the length of every signature list. */
  public static final int NUM_SIGNATURES_PER_ROW = 270;

  public SignatureIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(
        namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, IndexType.INVERTED_SIGNATURE);
  }

  @Override
  protected double getMaxPrefixSum(
      double keysUniValue, double recordUniValue, double minSimilarity) {
    return getSignatureKeyingStrategy()
        .getMaxPrefixSumForSignatures(
            (int) Math.ceil(keysUniValue), recordUniValue, minSimilarity);
  }

  @Override
  protected KeyAndUniTransformedValue[] getKeysAndUniTransformedValues(
      LongTermsAndValues indexedRecord) {
    long[] keys = getDistinctSortedKeys(indexedRecord);
    KeyAndUniTransformedValue[] keysAndValues =
        new KeyAndUniTransformedValue[keys.length];
    double signatureUniTransformedValue =
        getSignatureKeyingStrategy().getSignatureUniTransformedValue();
    for (int i = 0; i < keys.length; ++i) {
      keysAndValues[i] =
          new KeyAndUniTransformedValue(keys[i], signatureUniTransformedValue);
    }
    return keysAndValues;
  }

  /**
   * Returns the record's distinct signatures. One record's signatures routinely collide, so unlike
   * terms they must be deduplicated before serving as keys.
   */
  @Override
  protected long[] getKeys(LongTermsAndValues indexedRecord) {
    if (indexedRecord.termsLength() == 0) {
      return new long[0];
    }
    return LongHashSet.from(
            getSignatureKeyingStrategy()
                .getSignatures(indexedRecord, NUM_SIGNATURES_PER_ROW))
        .toArray();
  }

  private long[] getDistinctSortedKeys(LongTermsAndValues indexedRecord) {
    long[] keys = getKeys(indexedRecord);
    Arrays.sort(keys);
    return keys;
  }

  @Override
  protected float getValueAtKey(LongTermsAndValues indexedRecord, long key) {
    return (float) getSignatureKeyingStrategy().getSignatureUniTransformedValue();
  }
}
