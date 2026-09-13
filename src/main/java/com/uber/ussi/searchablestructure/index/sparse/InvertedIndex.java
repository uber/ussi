/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/** Exact inverted index for sparse terms and values. */
public final class InvertedIndex extends BaseTermKeyedIndex {

  public InvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, SparseKeyType.EXACT_TERM);
  }

  /** A row's own terms key its inverted lists, so a row has to already be a sparse record. */
  @Override
  protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    validateSparseRecordType(termsAndValues, source);
  }
}
