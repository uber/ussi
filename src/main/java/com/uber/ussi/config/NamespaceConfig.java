/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-agnostic USSI namespace configuration.
 *
 * <p>This contains only the fields required by the memory-only index.
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

  public void validate() {
    List<String> violations = collectStructuralViolations();
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(formatViolations(violations));
    }
  }

  public List<String> collectStructuralViolations() {
    List<String> violations = new ArrayList<>();
    checkNonNegative(violations, "minTermsAndValuesLength", minTermsAndValuesLength);
    checkNonNegative(violations, "maxTermsAndValuesLength", maxTermsAndValuesLength);
    checkPositive(violations, "maxCacheSize", maxCacheSize);
    checkNonBlank(violations, "cacheType", cacheType);
    checkNonBlank(violations, "indexType", indexType);
    checkNonBlank(violations, "comparatorType", comparatorType);
    checkNonBlank(violations, "comparatorNormalizerType", comparatorNormalizerType);
    if (maxNumSearchableStructures <= 2) {
      violations.add(
          "maxNumSearchableStructures must be > 2, got " + maxNumSearchableStructures + ".");
    }
    checkPositive(violations, "maxNumSimilarities", maxNumSimilarities);
    if (minTermsAndValuesLength > maxTermsAndValuesLength) {
      violations.add(
          "minTermsAndValuesLength must be <= maxTermsAndValuesLength, got "
              + minTermsAndValuesLength
              + " and "
              + maxTermsAndValuesLength
              + ".");
    }
    return violations;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static void checkNonBlank(List<String> violations, String name, String value) {
    if (value == null || value.trim().isEmpty()) {
      violations.add(name + " must be a non-blank string.");
    }
  }

  private static void checkPositive(List<String> violations, String name, int value) {
    if (value <= 0) {
      violations.add(name + " must be > 0, got " + value + ".");
    }
  }

  private static void checkNonNegative(List<String> violations, String name, int value) {
    if (value < 0) {
      violations.add(name + " must be >= 0, got " + value + ".");
    }
  }

  private static String formatViolations(List<String> violations) {
    if (violations.size() == 1) {
      return violations.get(0);
    }
    StringBuilder sb =
        new StringBuilder("NamespaceConfig has ").append(violations.size()).append(" violations:");
    for (String violation : violations) {
      sb.append("\n  - ").append(violation);
    }
    return sb.toString();
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
