/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.dense.DenseMatrixIndex;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import com.uber.ussi.searchablestructure.index.sparse.InvertedIndex;
import com.uber.ussi.searchablestructure.index.sparse.SignatureIndex;
import com.uber.ussi.searchablestructure.index.sparse.SparseIndex;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Factory for the ERD index new(config, rows) API. */
public final class IndexFactory {
  private static final Set<String> SPARSE_CANDIDATE_GENERATOR_INDEX_TYPES =
      Set.of("inverted", "sparse", "signature");
  private static final Set<String> MERGE_CANDIDATE_VERIFICATION_INDEX_TYPES =
      Set.of("sparse", "signature");

  public enum IndexType {
    GENERIC,
    DENSE,
    INVERTED,
    SIGNATURE,
    SPARSE
  }

  private IndexFactory() {}

  /** Returns whether the index type keeps the uni-sorted inverted lists the generators need. */
  public static boolean supportsSparseCandidateGenerator(String indexType) {
    return SPARSE_CANDIDATE_GENERATOR_INDEX_TYPES.contains(indexType.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns whether merge search has to verify its candidates through the comparator. The
   * approximate index types key their inverted lists by signature rather than by term, so a
   * candidate's similarity cannot be derived from the inverted-list values alone.
   */
  public static boolean mergeRequiresCandidateVerification(String indexType) {
    return MERGE_CANDIDATE_VERIFICATION_INDEX_TYPES.contains(indexType.toLowerCase(Locale.ROOT));
  }

  public static Index createIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String indexType = namespaceConfig.getIndexType().toLowerCase(Locale.ROOT);
    if (indexType.equals(IndexType.GENERIC.name().toLowerCase(Locale.ROOT))) {
      return new GenericIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }
    if (indexType.equals(IndexType.DENSE.name().toLowerCase(Locale.ROOT))) {
      return new DenseMatrixIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }
    if (indexType.equals(IndexType.INVERTED.name().toLowerCase(Locale.ROOT))) {
      return new InvertedIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }
    if (indexType.equals(IndexType.SIGNATURE.name().toLowerCase(Locale.ROOT))) {
      return new SignatureIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }
    if (indexType.equals(IndexType.SPARSE.name().toLowerCase(Locale.ROOT))) {
      return new SparseIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }

    throw new IndexCreationError(String.format("Unsupported index type (%s).", indexType));
  }
}
