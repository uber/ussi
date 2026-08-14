/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.SearchableStructure;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringModule;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import java.util.List;
import java.util.Objects;

/** Delete-only searchable structure built from rows graduated out of a cache. */
public abstract class Index implements SearchableStructure, AutoCloseable {
  public static final String MAX_PRE_FILTERING_ROWS_RATIO = "max_pre_filtering_rows_ratio";
  public static final String METADATA_FILTERING_STRATEGY = "metadata_filtering_strategy";
  private static final double DEFAULT_MAX_PRE_FILTERING_ROWS_RATIO = 0.1d;

  protected final NamespaceConfig namespaceConfig;
  protected final Comparator comparator;
  protected final LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap;
  protected final MetadataFilteringModule metadataFilteringModule;
  protected final double maxPreFilteringRowsRatio;
  protected final MetadataFilteringStrategy metadataFilteringStrategy;
  private final LongHashSet deletedRowNums;

  protected Index(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    this.namespaceConfig = Objects.requireNonNull(namespaceConfig, "namespaceConfig");
    this.namespaceConfig.validate();
    this.comparator = ComparatorFactory.createComparator(namespaceConfig);
    this.maxPreFilteringRowsRatio = parseMaxPreFilteringRowsRatio(namespaceConfig);
    this.metadataFilteringStrategy = parseMetadataFilteringStrategy(namespaceConfig);
    this.rowNumToTermsAndValuesMap =
        new LongObjectHashMap<>(Objects.requireNonNull(rowNumToTermsAndValuesMap, "rows"));
    this.deletedRowNums = new LongHashSet();
    this.metadataFilteringModule = new MetadataFilteringModule();
    LongObjectHashMap<LongMeta> metadataByRow =
        rowNumToMetaMap == null ? new LongObjectHashMap<>() : rowNumToMetaMap;
    for (LongObjectCursor<LongTermsAndValues> entry : this.rowNumToTermsAndValuesMap) {
      LongMeta metadata = metadataByRow.getOrDefault(entry.key, LongMeta.empty());
      metadataFilteringModule.put(entry.key, metadata);
    }
  }

  @Override
  public final boolean delete(long rowNum) {
    if (!rowNumToTermsAndValuesMap.containsKey(rowNum) || deletedRowNums.contains(rowNum)) {
      return false;
    }
    deletedRowNums.add(rowNum);
    metadataFilteringModule.delete(rowNum);
    onRowDeleted(rowNum);
    return true;
  }

  /** Hook invoked after a row is marked deleted so composite indexes can update child indexes. */
  protected void onRowDeleted(long rowNum) {}

  @Override
  public final LongObjectHashMap<LongTermsAndValues> getAll() {
    LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (!isDeleted(entry.key)) {
        rows.put(entry.key, entry.value);
      }
    }
    return rows;
  }

  public final LongObjectHashMap<LongMeta> getAllMetadata() {
    return metadataFilteringModule.getAllMetadata();
  }

  public final int size() {
    return rowNumToTermsAndValuesMap.size() - deletedRowNums.size();
  }

  public final boolean isEmpty() {
    return rowNumToTermsAndValuesMap.isEmpty();
  }

  @Override
  public void close() {}

  @Override
  public abstract List<RowNumAndSimilarity> getNearestNeighbors(
      int k, LongTermsAndValues record, MetaFilter metadataFilter);

  @Override
  public abstract List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);

  protected final boolean matchesMetaFilter(long rowNum, MetaFilter metadataFilter) {
    return metadataFilteringModule.doesMatch(rowNum, metadataFilter);
  }

  protected final boolean hasMetadataFilter(MetaFilter metadataFilter) {
    return metadataFilter != null && !metadataFilter.isEmpty();
  }

  protected final PreFilteringResult getMatchingRowNumsIfUnderPreFilteringLimit(
      MetaFilter metadataFilter) {
    return metadataFilteringModule.getMatchingRowNumsIfUnderLimit(
        metadataFilter, getMaxPreFilteringNumRows());
  }

  protected boolean supportsInFiltering() {
    return false;
  }

  /**
   * Expands the unfiltered candidate pool for post-filtering. Without this, a kNN search could keep
   * only globally-nearest rows that are later removed by metadata filtering and miss matching rows
   * just below the initial top-k boundary.
   */
  protected final int getPostFilteringMaxResults(int maxResults, MetaFilter metadataFilter) {
    if (!hasMetadataFilter(metadataFilter)) {
      return maxResults;
    }
    int numRowsMatching = metadataFilteringModule.getNumRowsMatching(metadataFilter);
    if (numRowsMatching == 0) {
      return 0;
    }
    double expansionRatio = (double) size() / numRowsMatching;
    int expandedMaxResults = (int) Math.ceil(maxResults * expansionRatio);
    return Math.min(
        namespaceConfig.getMaxNumSimilarities(), Math.max(maxResults, expandedMaxResults));
  }

  protected final boolean isDeleted(long rowNum) {
    return deletedRowNums.contains(rowNum);
  }

  private int getMaxPreFilteringNumRows() {
    return (int) Math.floor(size() * maxPreFilteringRowsRatio);
  }

  private static double parseMaxPreFilteringRowsRatio(NamespaceConfig namespaceConfig) {
    String rawValue = namespaceConfig.getIndexParams().get(MAX_PRE_FILTERING_ROWS_RATIO);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return DEFAULT_MAX_PRE_FILTERING_ROWS_RATIO;
    }
    double ratio;
    try {
      ratio = Double.parseDouble(rawValue);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format("%s must be a double in [0.0, 1.0].", MAX_PRE_FILTERING_ROWS_RATIO), e);
    }
    if (ratio < 0.0d || ratio > 1.0d) {
      throw new IllegalArgumentException(
          String.format("%s must be in [0.0, 1.0], got %s.", MAX_PRE_FILTERING_ROWS_RATIO, ratio));
    }
    return ratio;
  }

  private static MetadataFilteringStrategy parseMetadataFilteringStrategy(
      NamespaceConfig namespaceConfig) {
    String rawValue = namespaceConfig.getIndexParams().get(METADATA_FILTERING_STRATEGY);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return MetadataFilteringStrategy.AUTO;
    }
    try {
      return MetadataFilteringStrategy.fromIndexParam(rawValue);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("Unsupported %s (%s).", METADATA_FILTERING_STRATEGY, rawValue), e);
    }
  }
}
