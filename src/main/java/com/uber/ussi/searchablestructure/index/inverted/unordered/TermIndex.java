/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.unordered;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.index.inverted.BaseTermKeyedIndex;
import com.uber.ussi.searchablestructure.index.inverted.KeyType;

/**
 * Exact index over sparse terms and values, keyed by the terms themselves.
 *
 * <p>Every index in the inverted family keeps inverted lists. This is the one whose lists are
 * keyed by a row's own terms and nothing derived from them, so a query and a candidate sharing a
 * key share a term, and the terms they share determine their similarity exactly rather than
 * bounding it.
 */
public final class TermIndex extends BaseTermKeyedIndex {

  public TermIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, KeyType.EXACT_TERM);
  }

  /** A row's own terms key its inverted lists, so a row has to already be a sparse record. */
  @Override
  protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    validateSparseRecordType(termsAndValues, source);
  }
}
