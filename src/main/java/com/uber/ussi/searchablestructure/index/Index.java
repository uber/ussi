/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorConfigValidator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.SearchableStructure;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import java.util.List;
import java.util.Objects;

/**
 * Delete-only searchable structure built from rows graduated out of a cache.
 *
 * <p>Deletions are soft: the row joins a tombstone set and scoring skips it. Tombstoned rows are
 * dropped only when the index is rebuilt from {@link #getAll}.
 *
 * <p>What holds the rows is left to the index. {@link RowStoringIndex} holds its own and answers
 * for them, which is what all but one index does. An index built from other indexes holds none and
 * answers from the indexes it is built from, so that its rows are counted and returned once rather
 * than kept twice.
 */
public abstract class Index implements SearchableStructure, AutoCloseable {
  public static final String MAX_PRE_FILTERING_ROWS_RATIO = "max_pre_filtering_rows_ratio";
  public static final String METADATA_FILTERING_STRATEGY = "metadata_filtering_strategy";
  private static final double DEFAULT_MAX_PRE_FILTERING_ROWS_RATIO = 0.1d;

  protected final NamespaceConfig namespaceConfig;
  protected final Comparator comparator;
  protected final double maxPreFilteringRowsRatio;
  protected final MetadataFilteringStrategy metadataFilteringStrategy;

  protected Index(NamespaceConfig namespaceConfig) {
    this.namespaceConfig = Objects.requireNonNull(namespaceConfig, "namespaceConfig");
    this.namespaceConfig.validate(
        IndexConfigValidator.getInstance(), ComparatorConfigValidator.getInstance());
    this.comparator = ComparatorFactory.createComparator(namespaceConfig);
    this.maxPreFilteringRowsRatio = parseMaxPreFilteringRowsRatio(namespaceConfig);
    this.metadataFilteringStrategy = parseMetadataFilteringStrategy(namespaceConfig);
  }

  /** Marks {@code rowNum} deleted, and reports whether this index held it undeleted. */
  @Override
  public abstract boolean delete(long rowNum);

  /** The rows still held, which is what a rebuild of this index is given. */
  @Override
  public abstract LongObjectHashMap<LongTermsAndValues> getAll();

  /** The metadata of the rows still held. */
  public abstract LongObjectHashMap<LongMeta> getAllMetadata();

  /** Rows held and not deleted. */
  public abstract int size();

  /** Whether this index was built with no rows at all, deleted or otherwise. */
  public abstract boolean isEmpty();

  @Override
  public void close() {}

  @Override
  public abstract List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity);

  @Override
  public abstract List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);

  protected final boolean hasMetadataFilter(MetaFilter metadataFilter) {
    return metadataFilter != null && !metadataFilter.isEmpty();
  }

  private static double parseMaxPreFilteringRowsRatio(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleIndexParam(
        MAX_PRE_FILTERING_ROWS_RATIO, DEFAULT_MAX_PRE_FILTERING_ROWS_RATIO);
  }

  private static MetadataFilteringStrategy parseMetadataFilteringStrategy(
      NamespaceConfig namespaceConfig) {
    String rawValue = namespaceConfig.getIndexParam(METADATA_FILTERING_STRATEGY);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return MetadataFilteringStrategy.AUTO;
    }
    MetadataFilteringStrategy strategy =
        ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, rawValue);
    if (strategy == null) {
      throw new IllegalArgumentException(
          ConfigVocabulary.unsupported(
              METADATA_FILTERING_STRATEGY, rawValue, MetadataFilteringStrategy.class));
    }
    return strategy;
  }
}
