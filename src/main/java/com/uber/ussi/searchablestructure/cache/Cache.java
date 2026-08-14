/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.SearchableStructure;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringModule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writable in-memory searchable structure described by the USSI ERD.
 *
 * <p>This class is not thread-safe on its own. Concurrency is handled by the owning {@link
 * com.uber.ussi.NearestNeighborSearchIndex}, which serializes all access with
 * a single read/write lock (mutations under the write lock, searches under the read lock).
 */
public abstract class Cache implements SearchableStructure {
  protected static final int CACHE_INITIAL_CAPACITY = 10000;

  protected final NamespaceConfig namespaceConfig;
  protected final Comparator comparator;
  protected final LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap;
  protected final MetadataFilteringModule metadataFilteringModule;

  private long nextRowNum;

  protected Cache(NamespaceConfig namespaceConfig) {
    this.namespaceConfig = Objects.requireNonNull(namespaceConfig, "namespaceConfig");
    this.namespaceConfig.validate();
    this.comparator = ComparatorFactory.createComparator(namespaceConfig);
    this.rowNumToTermsAndValuesMap = new LongObjectHashMap<>();
    this.metadataFilteringModule = new MetadataFilteringModule();
    this.nextRowNum = 0;
  }

  public final long insert(LongTermsAndValues record, Map<String, String> metadata) {
    if (nextRowNum == Long.MAX_VALUE) {
      throw new IllegalStateException("nextRowNum has reached Long.MAX_VALUE.");
    }
    long rowNum = nextRowNum++;
    put(rowNum, record, metadata);
    return rowNum;
  }

  public final boolean insertWithRowNum(
      long rowNum, LongTermsAndValues record, Map<String, String> metadata) {
    if (rowNumToTermsAndValuesMap.containsKey(rowNum)) {
      return false;
    }
    put(rowNum, record, metadata);
    if (rowNum >= nextRowNum) {
      nextRowNum = rowNum + 1;
    }
    return true;
  }

  public final boolean update(
      long rowNum, LongTermsAndValues record, Map<String, String> metadata) {
    if (!rowNumToTermsAndValuesMap.containsKey(rowNum)) {
      return false;
    }
    LongMeta newMeta = toLongMeta(metadata);
    deleteRow(rowNum);
    put(rowNum, record, newMeta);
    return true;
  }

  @Override
  public final boolean delete(long rowNum) {
    return deleteRow(rowNum);
  }

  @Override
  public final LongObjectHashMap<LongTermsAndValues> getAll() {
    return new LongObjectHashMap<>(rowNumToTermsAndValuesMap);
  }

  public final LongObjectHashMap<LongMeta> getAllMetadata() {
    return metadataFilteringModule.getAllMetadata();
  }

  public final int size() {
    return rowNumToTermsAndValuesMap.size();
  }

  public final boolean isEmpty() {
    return rowNumToTermsAndValuesMap.isEmpty();
  }

  public final boolean isReadyForGraduation() {
    return size() >= namespaceConfig.getMaxCacheSize();
  }

  @Override
  public final List<RowNumAndSimilarity> getNearestNeighbors(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    return getNearestNeighborsLocked(k, record, metadataFilter);
  }

  @Override
  public final List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    return getSimilarRowNumsLocked(minSimilarity, record, metadataFilter);
  }

  protected abstract List<RowNumAndSimilarity> getNearestNeighborsLocked(
      int k, LongTermsAndValues record, MetaFilter metadataFilter);

  protected abstract List<RowNumAndSimilarity> getSimilarRowNumsLocked(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);

  protected final boolean matchesMetaFilter(long rowNum, MetaFilter metadataFilter) {
    return metadataFilteringModule.doesMatch(rowNum, metadataFilter);
  }

  /**
   * Hook invoked after a row is added, so subclasses can maintain auxiliary search structures.
   * Updates invoke {@link #onRowDeleted} for the old record followed by this hook for the new one.
   */
  protected void onRowInserted(long rowNum, LongTermsAndValues record) {}

  /** Hook invoked after a row is removed, so subclasses can maintain auxiliary structures. */
  protected void onRowDeleted(long rowNum, LongTermsAndValues record) {}

  private void put(long rowNum, LongTermsAndValues record, Map<String, String> metadata) {
    put(rowNum, record, toLongMeta(metadata));
  }

  private void put(long rowNum, LongTermsAndValues record, LongMeta metadata) {
    Objects.requireNonNull(record, "record");
    if (!metadataFilteringModule.put(rowNum, metadata)) {
      throw new IllegalStateException(
          String.format("rowNum %s already exists in metadata filtering module.", rowNum));
    }
    rowNumToTermsAndValuesMap.put(rowNum, record);
    onRowInserted(rowNum, record);
  }

  private boolean deleteRow(long rowNum) {
    if (!rowNumToTermsAndValuesMap.containsKey(rowNum)) {
      return false;
    }
    LongTermsAndValues record = rowNumToTermsAndValuesMap.remove(rowNum);
    metadataFilteringModule.delete(rowNum);
    if (record != null) {
      onRowDeleted(rowNum, record);
    }
    return true;
  }

  /** Encodes all non-null metadata keys and values for metadata filter evaluation. */
  private LongMeta toLongMeta(Map<String, String> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return LongMeta.empty();
    }
    Map<String, String> filteredMetadata = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : metadata.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      String key = normalize(entry.getKey());
      String value = normalize(entry.getValue());
      filteredMetadata.put(key, value);
    }
    if (filteredMetadata.isEmpty()) {
      return LongMeta.empty();
    }
    return new LongMeta(filteredMetadata, /* requireLongKeysAndValues */ false);
  }

  private static String normalize(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
