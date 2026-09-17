/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import com.uber.ussi.utils.ConfigKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Platform-agnostic USSI namespace configuration.
 *
 * <p>This class checks the structural invariants it can see on its own; each layer contributes a
 * {@link NamespaceConfigValidator} for the params and cross-field rules only that layer knows.
 */
public final class NamespaceConfig {
  private final int minTermsAndValuesLength;
  private final int maxTermsAndValuesLength;
  private final int maxCacheSize;
  private final String cacheType;
  private final Map<String, String> cacheParams;
  private final String indexType;
  private final Map<String, String> indexParams;
  private final String comparatorType;
  private final Map<String, String> comparatorParams;
  private final String comparatorNormalizerType;
  private final Map<String, String> comparatorNormalizerParams;
  private final int maxNumSearchableStructures;
  private final int maxNumSimilarities;

  private NamespaceConfig(Builder builder) {
    this.minTermsAndValuesLength = builder.minTermsAndValuesLength;
    this.maxTermsAndValuesLength = builder.maxTermsAndValuesLength;
    this.maxCacheSize = builder.maxCacheSize;
    this.cacheType = normalizeType(builder.cacheType);
    this.cacheParams = unmodifiableMap(builder.cacheParams);
    this.indexType = normalizeType(builder.indexType);
    this.indexParams = unmodifiableMap(builder.indexParams);
    this.comparatorType = normalizeType(builder.comparatorType);
    this.comparatorParams = unmodifiableMap(builder.comparatorParams);
    this.comparatorNormalizerType = normalizeType(builder.comparatorNormalizerType);
    this.comparatorNormalizerParams = unmodifiableMap(builder.comparatorNormalizerParams);
    this.maxNumSearchableStructures = builder.maxNumSearchableStructures;
    this.maxNumSimilarities = builder.maxNumSimilarities;
  }

  public int getMinTermsAndValuesLength() {
    return minTermsAndValuesLength;
  }

  public int getMaxTermsAndValuesLength() {
    return maxTermsAndValuesLength;
  }

  public int getMaxCacheSize() {
    return maxCacheSize;
  }

  public String getCacheType() {
    return cacheType;
  }

  public Map<String, String> getCacheParams() {
    return cacheParams;
  }

  public String getIndexType() {
    return indexType;
  }

  public Map<String, String> getIndexParams() {
    return indexParams;
  }

  public String getComparatorType() {
    return comparatorType;
  }

  public Map<String, String> getComparatorParams() {
    return comparatorParams;
  }

  public String getComparatorNormalizerType() {
    return comparatorNormalizerType;
  }

  public Map<String, String> getComparatorNormalizerParams() {
    return comparatorNormalizerParams;
  }

  public int getMaxNumSimilarities() {
    return maxNumSimilarities;
  }

  public int getMaxNumSearchableStructures() {
    return maxNumSearchableStructures;
  }

  @Nullable
  public String getIndexParam(String key) {
    return NamespaceConfigParams.getParam(indexParams, key);
  }

  @Nullable
  public String getCacheParam(String key) {
    return NamespaceConfigParams.getParam(cacheParams, key);
  }

  @Nullable
  public String getComparatorParam(String key) {
    return NamespaceConfigParams.getParam(comparatorParams, key);
  }

  public double readDoubleIndexParam(String key, double defaultValue) {
    return NamespaceConfigParams.readDoubleParam(indexParams, key, defaultValue);
  }

  public double readDoubleCacheParam(String key, double defaultValue) {
    return NamespaceConfigParams.readDoubleParam(cacheParams, key, defaultValue);
  }

  public CandidateGeneratorType getCandidateGeneratorType() {
    return parseCandidateGeneratorType(indexParams);
  }

  public PopularTermDiscardScope getIndexPopularTermDiscardScope() {
    return parsePopularTermDiscardScope(indexParams);
  }

  public PopularTermDiscardScope getCachePopularTermDiscardScope() {
    return parsePopularTermDiscardScope(cacheParams);
  }

  /** Throws if this config is structurally invalid or a supplied validator reports a violation. */
  public void validate(NamespaceConfigValidator... validators) {
    List<String> violations = collectViolations(validators);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(ConfigViolations.format(violations));
    }
  }

  public List<String> collectViolations(NamespaceConfigValidator... validators) {
    List<String> violations = collectStructuralViolations();
    for (NamespaceConfigValidator validator : validators) {
      validator.collectViolations(this, violations);
    }
    return violations;
  }

  /** Returns the violations visible from the config alone, without consulting any layer. */
  public List<String> collectStructuralViolations() {
    List<String> violations = new ArrayList<>();
    ConfigViolations.checkNonNegative(
        violations, "minTermsAndValuesLength", minTermsAndValuesLength);
    ConfigViolations.checkNonNegative(
        violations, "maxTermsAndValuesLength", maxTermsAndValuesLength);
    ConfigViolations.checkPositive(violations, "maxCacheSize", maxCacheSize);
    ConfigViolations.checkNonBlank(violations, "cacheType", cacheType);
    ConfigViolations.checkNonBlank(violations, "indexType", indexType);
    ConfigViolations.checkNonBlank(violations, "comparatorType", comparatorType);
    ConfigViolations.checkNonBlank(
        violations, "comparatorNormalizerType", comparatorNormalizerType);
    if (maxNumSearchableStructures <= 2) {
      violations.add(
          "maxNumSearchableStructures must be > 2, got " + maxNumSearchableStructures + ".");
    }
    ConfigViolations.checkPositive(violations, "maxNumSimilarities", maxNumSimilarities);
    if (minTermsAndValuesLength > maxTermsAndValuesLength) {
      violations.add(
          "minTermsAndValuesLength must be <= maxTermsAndValuesLength, got "
              + minTermsAndValuesLength
              + " and "
              + maxTermsAndValuesLength
              + ".");
    }
    collectCandidateGeneratorTypeViolations(violations);
    collectPopularTermDiscardScopeViolations(violations);
    return violations;
  }

  private void collectPopularTermDiscardScopeViolations(List<String> violations) {
    for (Map<String, String> params : List.of(indexParams, cacheParams)) {
      try {
        parsePopularTermDiscardScope(params);
      } catch (IllegalArgumentException e) {
        violations.add(e.getMessage());
      }
    }
  }

  private void collectCandidateGeneratorTypeViolations(List<String> violations) {
    try {
      parseCandidateGeneratorType(indexParams);
    } catch (IllegalArgumentException e) {
      violations.add(e.getMessage());
    }
  }

  private static PopularTermDiscardScope parsePopularTermDiscardScope(Map<String, String> params) {
    String rawValue = NamespaceConfigParams.getParam(params, ConfigKeys.POPULAR_TERM_DISCARD_SCOPE);
    if (NamespaceConfigParams.isBlank(rawValue)) {
      return PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION;
    }
    PopularTermDiscardScope scope =
        ConfigVocabulary.fromParamValue(PopularTermDiscardScope.class, rawValue);
    if (scope == null) {
      throw new IllegalArgumentException(
          ConfigVocabulary.unsupported(
              ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, rawValue, PopularTermDiscardScope.class));
    }
    return scope;
  }

  private static CandidateGeneratorType parseCandidateGeneratorType(
      Map<String, String> indexParams) {
    String rawValue = NamespaceConfigParams.getParam(indexParams, ConfigKeys.CANDIDATE_GENERATOR);
    if (NamespaceConfigParams.isBlank(rawValue)) {
      return CandidateGeneratorType.SPARS;
    }
    CandidateGeneratorType generatorType =
        ConfigVocabulary.fromParamValue(CandidateGeneratorType.class, rawValue);
    if (generatorType == null) {
      throw new IllegalArgumentException(
          ConfigVocabulary.unsupported(
              ConfigKeys.CANDIDATE_GENERATOR, rawValue, CandidateGeneratorType.class));
    }
    return generatorType;
  }

  /**
   * Which phases of a search a discarded high-popularity term is absent from. Which terms qualify
   * for discarding is governed by {@link ConfigKeys#MAX_FRACTION_IDS_PER_TERM}.
   */
  public enum PopularTermDiscardScope implements ConfigVocabulary {
    /**
     * The term is absent from candidate generation and verification, so a search reports the
     * similarity between the records with it removed, and recall is exact under that measure.
     */
    CANDIDATES_AND_VERIFICATION,

    /**
     * The term is absent from candidate generation only, so a search reports the similarity between
     * the records as supplied. Recall is not exact: pruning measures similarity without the
     * discarded terms, so a record within the minimum similarity can be pruned before verification.
     */
    CANDIDATES_ONLY
  }

  /** Candidate-generation algorithm for the immutable inverted indexes. */
  public enum CandidateGeneratorType implements ConfigVocabulary {
    SPARS,
    SPARS_MERGE
  }

  public static Builder builder() {
    return new Builder();
  }

  private static Map<String, String> unmodifiableMap(Map<String, String> map) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(map));
  }

  private static String normalizeType(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NamespaceConfig)) {
      return false;
    }
    NamespaceConfig that = (NamespaceConfig) o;
    return minTermsAndValuesLength == that.minTermsAndValuesLength
        && maxTermsAndValuesLength == that.maxTermsAndValuesLength
        && maxCacheSize == that.maxCacheSize
        && maxNumSearchableStructures == that.maxNumSearchableStructures
        && maxNumSimilarities == that.maxNumSimilarities
        && Objects.equals(cacheType, that.cacheType)
        && cacheParams.equals(that.cacheParams)
        && Objects.equals(indexType, that.indexType)
        && indexParams.equals(that.indexParams)
        && Objects.equals(comparatorType, that.comparatorType)
        && comparatorParams.equals(that.comparatorParams)
        && Objects.equals(comparatorNormalizerType, that.comparatorNormalizerType)
        && comparatorNormalizerParams.equals(that.comparatorNormalizerParams);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        minTermsAndValuesLength,
        maxTermsAndValuesLength,
        maxCacheSize,
        cacheType,
        cacheParams,
        indexType,
        indexParams,
        comparatorType,
        comparatorParams,
        comparatorNormalizerType,
        comparatorNormalizerParams,
        maxNumSearchableStructures,
        maxNumSimilarities);
  }

  @Override
  public String toString() {
    return "NamespaceConfig{"
        + "minTermsAndValuesLength="
        + minTermsAndValuesLength
        + ", maxTermsAndValuesLength="
        + maxTermsAndValuesLength
        + ", maxCacheSize="
        + maxCacheSize
        + ", cacheType='"
        + cacheType
        + '\''
        + ", indexType='"
        + indexType
        + '\''
        + ", comparatorType='"
        + comparatorType
        + '\''
        + ", comparatorNormalizerType='"
        + comparatorNormalizerType
        + '\''
        + ", maxNumSearchableStructures="
        + maxNumSearchableStructures
        + ", maxNumSimilarities="
        + maxNumSimilarities
        + '}';
  }

  public static final class Builder {
    private int minTermsAndValuesLength;
    private int maxTermsAndValuesLength;
    private int maxCacheSize;
    private String cacheType = "";
    private Map<String, String> cacheParams = new LinkedHashMap<>();
    private String indexType = "";
    private Map<String, String> indexParams = new LinkedHashMap<>();
    private String comparatorType = "";
    private Map<String, String> comparatorParams = new LinkedHashMap<>();
    private String comparatorNormalizerType = "";
    private Map<String, String> comparatorNormalizerParams = new LinkedHashMap<>();
    private int maxNumSearchableStructures;
    private int maxNumSimilarities;

    private Builder() {}

    public Builder minTermsAndValuesLength(int minTermsAndValuesLength) {
      this.minTermsAndValuesLength = minTermsAndValuesLength;
      return this;
    }

    public Builder maxTermsAndValuesLength(int maxTermsAndValuesLength) {
      this.maxTermsAndValuesLength = maxTermsAndValuesLength;
      return this;
    }

    public Builder maxCacheSize(int maxCacheSize) {
      this.maxCacheSize = maxCacheSize;
      return this;
    }

    public Builder cacheType(String cacheType) {
      this.cacheType = cacheType;
      return this;
    }

    public Builder cacheParams(Map<String, String> cacheParams) {
      this.cacheParams =
          cacheParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cacheParams);
      return this;
    }

    public Builder indexType(String indexType) {
      this.indexType = indexType;
      return this;
    }

    public Builder indexParams(Map<String, String> indexParams) {
      this.indexParams =
          indexParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(indexParams);
      return this;
    }

    public Builder comparatorType(String comparatorType) {
      this.comparatorType = comparatorType;
      return this;
    }

    public Builder comparatorParams(Map<String, String> comparatorParams) {
      this.comparatorParams =
          comparatorParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(comparatorParams);
      return this;
    }

    public Builder comparatorNormalizerType(String comparatorNormalizerType) {
      this.comparatorNormalizerType = comparatorNormalizerType;
      return this;
    }

    public Builder comparatorNormalizerParams(Map<String, String> comparatorNormalizerParams) {
      this.comparatorNormalizerParams =
          comparatorNormalizerParams == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(comparatorNormalizerParams);
      return this;
    }

    public Builder maxNumSimilarities(int maxNumSimilarities) {
      this.maxNumSimilarities = maxNumSimilarities;
      return this;
    }

    public Builder maxNumSearchableStructures(int maxNumSearchableStructures) {
      this.maxNumSearchableStructures = maxNumSearchableStructures;
      return this;
    }

    public NamespaceConfig build() {
      return new NamespaceConfig(this);
    }
  }
}
