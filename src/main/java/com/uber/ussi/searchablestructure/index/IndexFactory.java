/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.dense.DenseMatrixIndex;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import com.uber.ussi.searchablestructure.index.sparse.TermIndex;
import com.uber.ussi.searchablestructure.index.sparse.SequenceIndex;
import com.uber.ussi.searchablestructure.index.sparse.SignatureIndex;
import com.uber.ussi.searchablestructure.index.sparse.SparseIndex;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Factory for the ERD index new(config, rows) API. */
public final class IndexFactory {
  private static final Set<String> SPARSE_CANDIDATE_GENERATOR_INDEX_TYPES =
      Set.of("term", "sequence", "signature", "sparse");
  private static final Set<String> MERGE_CANDIDATE_VERIFICATION_INDEX_TYPES =
      Set.of("sequence", "signature", "sparse");

  /**
   * The index types, each paired with the {@link RecordType} it stores, or with null for the
   * generic index, which scans and scores through the comparator and so never reads a record's
   * layout itself.
   */
  public enum IndexType {
    GENERIC(null),
    DENSE(RecordType.DENSE),
    TERM(RecordType.SPARSE),
    SEQUENCE(RecordType.SEQUENCE),
    SIGNATURE(RecordType.SPARSE),
    SPARSE(RecordType.SPARSE);

    @Nullable private final RecordType recordType;

    IndexType(@Nullable RecordType recordType) {
      this.recordType = recordType;
    }

    @Nullable
    public RecordType getRecordType() {
      return recordType;
    }
  }

  private IndexFactory() {}

  /**
   * Returns the record type the index type stores, or null if it accepts every type. An
   * unrecognized index type is reported by {@link #createIndex}, so it is treated here as
   * accepting every type rather than reported twice.
   */
  @Nullable
  public static RecordType getRecordType(String indexType) {
    String normalizedIndexType = indexType.toLowerCase(Locale.ROOT);
    for (IndexType candidate : IndexType.values()) {
      if (candidate.name().toLowerCase(Locale.ROOT).equals(normalizedIndexType)) {
        return candidate.getRecordType();
      }
    }
    return null;
  }

  /** Returns whether the index type keeps the uni-sorted inverted lists the generators need. */
  public static boolean supportsSparseCandidateGenerator(String indexType) {
    return SPARSE_CANDIDATE_GENERATOR_INDEX_TYPES.contains(indexType.toLowerCase(Locale.ROOT));
  }

  /** Returns the index types of {@link #supportsSparseCandidateGenerator}, for error messages. */
  public static String describeSparseCandidateGeneratorIndexTypes() {
    return SPARSE_CANDIDATE_GENERATOR_INDEX_TYPES.stream()
        .sorted()
        .collect(Collectors.joining(", "));
  }

  /**
   * Returns whether merge search has to verify its candidates through the comparator, which is the
   * case for every index type whose inverted-list values do not determine a candidate's
   * similarity: the approximate ones key their lists by signature rather than by term, and the
   * sequence index keys them by elements whose order, which the lists do not record, is what the
   * similarity depends on.
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
    if (indexType.equals(IndexType.TERM.name().toLowerCase(Locale.ROOT))) {
      return new TermIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    }
    if (indexType.equals(IndexType.SEQUENCE.name().toLowerCase(Locale.ROOT))) {
      return new SequenceIndex(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
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
