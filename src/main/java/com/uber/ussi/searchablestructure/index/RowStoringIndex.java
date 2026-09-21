/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.MemoryFootprint;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.utils.metadata.MetadataFilteringModule;
import com.uber.ussi.searchablestructure.utils.metadata.PreFilteringResult;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An index that holds its own rows.
 *
 * <p>It keeps the rows, their metadata, and the tombstones of the rows deleted from it, and it
 * answers for all three.
 */
public abstract class RowStoringIndex extends Index {

  /**
   * Bytes one row occupies in the row map itself, being its key slot and the reference to its
   * record, at the load factor the map is kept at.
   */
  private static final long BYTES_PER_ROW_MAP_ENTRY = 24;

  /**
   * Bytes one record occupies beyond its two arrays, being the object header, the references to
   * those arrays, and the unilateral value.
   */
  private static final long BYTES_PER_RECORD_HEADER = 40;

  protected final LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap;
  protected final MetadataFilteringModule metadataFilteringModule;
  private int numDeletedRows;

  protected RowStoringIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig);
    this.rowNumToTermsAndValuesMap =
        new LongObjectHashMap<>(Objects.requireNonNull(rowNumToTermsAndValuesMap, "rows"));
    this.metadataFilteringModule = new MetadataFilteringModule();
    LongObjectHashMap<LongMeta> metadataByRow =
        rowNumToMetaMap == null ? new LongObjectHashMap<>() : rowNumToMetaMap;
    for (LongObjectCursor<LongTermsAndValues> entry : this.rowNumToTermsAndValuesMap) {
      LongMeta metadata = metadataByRow.getOrDefault(entry.key, LongMeta.empty());
      metadataFilteringModule.put(entry.key, metadata);
    }
  }

  /**
   * Marks the row deleted by replacing its record with one whose unilateral value is not a
   * number. Every path that must skip a deleted row already reads the record, so recognising one
   * costs nothing further, where a set of deleted row numbers would cost a lookup for every row
   * of every search.
   */
  @Override
  public final boolean delete(long rowNum) {
    LongTermsAndValues row = rowNumToTermsAndValuesMap.get(rowNum);
    if (row == null || isDeleted(row)) {
      return false;
    }
    rowNumToTermsAndValuesMap.put(rowNum, row.markAsDeleted());
    ++numDeletedRows;
    metadataFilteringModule.delete(rowNum);
    onRowDeleted(rowNum);
    return true;
  }

  /**
   * Called once when a row is deleted, so that a subclass holding a further form of the row
   * records the deletion in it too.
   */
  protected void onRowDeleted(long rowNum) {}

  /** Whether the row is one a search must skip, which a deleted row is. */
  protected static boolean isDeleted(@Nullable LongTermsAndValues row) {
    return row == null || Double.isNaN(row.getUniValue());
  }

  @Override
  public final LongObjectHashMap<LongTermsAndValues> getAll() {
    LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (!isDeleted(entry.value)) {
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
    return rowNumToTermsAndValuesMap.size() - numDeletedRows;
  }

  @Override
  public final boolean isEmpty() {
    return rowNumToTermsAndValuesMap.isEmpty();
  }

  @Override
  public MemoryFootprint getMemoryFootprint() {
    return new MemoryFootprint(getRowStorageBytes() + getMetadataStorageBytes(), 0);
  }

  /**
   * Bytes the row map holds, counting every row the map still has a slot for. A deleted row keeps
   * its slot and its arrays until the index is rebuilt, so this counts allocated rows rather than
   * live ones.
   */
  protected final long getRowStorageBytes() {
    return estimateRowMapBytes(rowNumToTermsAndValuesMap);
  }

  /** Bytes the structure answering metadata filters holds. */
  protected final long getMetadataStorageBytes() {
    return metadataFilteringModule.getEstimatedBytes();
  }

  /**
   * Bytes a map from rowNum to record holds, being its slots and the two arrays of every record in
   * it. A subclass keeping a further map of the same shape sizes it through this.
   */
  protected static long estimateRowMapBytes(LongObjectHashMap<LongTermsAndValues> rowMap) {
    long bytes = 0;
    for (LongObjectCursor<LongTermsAndValues> entry : rowMap) {
      bytes += BYTES_PER_ROW_MAP_ENTRY + BYTES_PER_RECORD_HEADER;
      bytes += (long) entry.value.termsLength() * Long.BYTES;
      bytes += (long) entry.value.valuesLength() * Float.BYTES;
    }
    return bytes;
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
    return isDeleted(rowNumToTermsAndValuesMap.get(rowNum));
  }

  private int getMaxPreFilteringNumRows() {
    return (int) Math.floor(size() * maxPreFilteringRowsRatio);
  }
}
