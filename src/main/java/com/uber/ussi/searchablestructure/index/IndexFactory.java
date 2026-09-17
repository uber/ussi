/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.inverted.HybridIndex;
import com.uber.ussi.searchablestructure.index.inverted.SignatureIndex;
import com.uber.ussi.searchablestructure.index.inverted.TermIndex;
import com.uber.ussi.searchablestructure.index.matrix.MatrixIndex;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import java.util.Objects;

/** Factory for the ERD index new(config, rows) API. */
public final class IndexFactory {

  private IndexFactory() {}

  public static Index createIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String configuredIndexType = namespaceConfig.getIndexType();
    IndexType indexType = ConfigVocabulary.fromParamValue(IndexType.class, configuredIndexType);
    if (indexType == null) {
      throw new IndexCreationError(
          ConfigVocabulary.unsupported("indexType", configuredIndexType, IndexType.class));
    }
    return switch (indexType) {
      case SCAN -> new ScanIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case MATRIX -> new MatrixIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case INVERTED_TERM ->
          new TermIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case INVERTED_SIGNATURE ->
          new SignatureIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case INVERTED_HYBRID ->
          new HybridIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    };
  }
}
