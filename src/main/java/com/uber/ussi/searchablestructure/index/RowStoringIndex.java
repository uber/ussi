/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringModule;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import java.util.Objects;

/**
 * An index that holds its own rows.
 *
 * <p>It keeps the rows, their metadata, and the tombstones of the rows deleted from it, and it
 * answers for all three.
 */
public abstract class RowStoringIndex extends Index {

  protected final LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap;
  protected final MetadataFilteringModule metadataFilteringModule;
  private final LongHashSet deletedRowNums;

  protected RowStoringIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig);
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
    return true;
  }

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

  @Override
  public final LongObjectHashMap<LongMeta> getAllMetadata() {
    return metadataFilteringModule.getAllMetadata();
  }

  @Override
  public final int size() {
    return rowNumToTermsAndValuesMap.size() - deletedRowNums.size();
  }

  @Override
  public final boolean isEmpty() {
    return rowNumToTermsAndValuesMap.isEmpty();
  }

  protected final boolean matchesMetaFilter(long rowNum, MetaFilter metadataFilter) {
    return metadataFilteringModule.doesMatch(rowNum, metadataFilter);
  }

  protected final PreFilteringResult getMatchingRowNumsIfUnderPreFilteringLimit(
      MetaFilter metadataFilter) {
    return metadataFilteringModule.getMatchingRowNumsIfUnderLimit(
        metadataFilter, getMaxPreFilteringNumRows());
  }

  /**
   * Expands the unfiltered candidate pool so post-filtering still reaches the matching rows that
   * sit just below the unexpanded top-k boundary.
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
}
