/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.inverted.HybridIndex;
import com.uber.ussi.searchablestructure.index.inverted.ShardedInvertedIndex;
import com.uber.ussi.searchablestructure.index.inverted.SignatureIndex;
import com.uber.ussi.searchablestructure.index.inverted.TermIndex;
import com.uber.ussi.searchablestructure.index.matrix.MatrixIndex;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import java.util.Objects;
import javax.annotation.Nullable;

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
    int numShards = numShardsFor(indexType, rowNumToTermsAndValuesMap.size());
    if (numShards > 1) {
      return new ShardedInvertedIndex(
          namespaceConfig,
          rowNumToTermsAndValuesMap,
          rowNumToMetaMap,
          numShards,
          indexType,
          (shardRows, shardMetadata, discardedTerms) ->
              createOneIndex(indexType, namespaceConfig, shardRows, shardMetadata, discardedTerms));
    }
    return createOneIndex(
        indexType, namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, null);
  }

  /**
   * One index over the rows given. A {@code discardedTerms} of null leaves the index to find the
   * popular terms over those rows, which is right only when they are the whole structure's.
   */
  private static Index createOneIndex(
      IndexType indexType,
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      @Nullable LongHashSet discardedTerms) {
    return switch (indexType) {
      case SCAN -> new ScanIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case MATRIX -> new MatrixIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
      case INVERTED_TERM ->
          new TermIndex(
              namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, discardedTerms);
      case INVERTED_SIGNATURE ->
          new SignatureIndex(
              namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, discardedTerms);
      case INVERTED_HYBRID ->
          new HybridIndex(
              namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, discardedTerms);
    };
  }

  /**
   * Shards to build {@code numRows} into, which is more than one only for the inverted indexes.
   *
   * <p>A scan index already divides the rows of one search between threads, and a matrix index
   * already divides one search inside its native scorer, both off the same budget. A shard on top
   * of either would divide rows already being divided and spend threads the budget has promised.
   */
  private static int numShardsFor(IndexType indexType, int numRows) {
    return switch (indexType) {
      case SCAN, MATRIX -> 1;
      case INVERTED_TERM, INVERTED_SIGNATURE, INVERTED_HYBRID ->
          ShardedInvertedIndex.numShardsFor(numRows);
    };
  }
}
