/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.metadata;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongLongHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongLongCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * ERD metadata filtering structure.
 *
 * <p>The first map points each rowNum to a metadataDictionaryKey. The second map points each
 * metadataDictionaryKey to the distinct metadata value and the rowNums sharing that value.
 */
public final class MetadataFilteringModule {
  private final LongLongHashMap rowNumToMetadataDictionaryKeyMap;
  private final LongObjectHashMap<MetadataDictionaryEntry> metadataDictionary;

  public MetadataFilteringModule() {
    this.rowNumToMetadataDictionaryKeyMap = new LongLongHashMap();
    this.metadataDictionary = new LongObjectHashMap<>();
  }

  public MetadataFilteringModule(LongObjectHashMap<LongMeta> rowNumToMetadataMap) {
    this();
    if (rowNumToMetadataMap != null) {
      for (LongObjectCursor<LongMeta> entry : rowNumToMetadataMap) {
        put(entry.key, entry.value);
      }
    }
  }

  public boolean put(long rowNum, LongMeta metadata) {
    Objects.requireNonNull(metadata, "metadata");
    if (rowNumToMetadataDictionaryKeyMap.containsKey(rowNum)) {
      return false;
    }
    long metadataDictionaryKey = metadata.longHashCode();
    MetadataDictionaryEntry entry = metadataDictionary.get(metadataDictionaryKey);
    if (entry == null) {
      entry = new MetadataDictionaryEntry(metadata);
      metadataDictionary.put(metadataDictionaryKey, entry);
    }
    if (!entry.getMetadata().equals(metadata)) {
      throw new IllegalStateException(
          String.format("metadataDictionaryKey collision for key %s.", metadataDictionaryKey));
    }
    entry.addRowNum(rowNum);
    rowNumToMetadataDictionaryKeyMap.put(rowNum, metadataDictionaryKey);
    return true;
  }

  public boolean delete(long rowNum) {
    if (!rowNumToMetadataDictionaryKeyMap.containsKey(rowNum)) {
      return false;
    }
    long metadataDictionaryKey = rowNumToMetadataDictionaryKeyMap.remove(rowNum);
    MetadataDictionaryEntry entry = metadataDictionary.get(metadataDictionaryKey);
    if (entry == null) {
      return false;
    }
    entry.removeRowNum(rowNum);
    if (entry.isEmpty()) {
      metadataDictionary.remove(metadataDictionaryKey);
    }
    return true;
  }

  public boolean doesMatch(long rowNum, MetaFilter metadataFilter) {
    if (metadataFilter == null || metadataFilter.isEmpty()) {
      return rowNumToMetadataDictionaryKeyMap.containsKey(rowNum);
    }
    MetadataDictionaryEntry entry = getMetadataDictionaryEntry(rowNum);
    return entry != null && metadataFilter.doesMatch(entry.getMetadata());
  }

  public int getNumRowsMatching(MetaFilter metadataFilter) {
    if (metadataFilter == null || metadataFilter.isEmpty()) {
      return rowNumToMetadataDictionaryKeyMap.size();
    }
    int numRowsMatching = 0;
    for (LongObjectCursor<MetadataDictionaryEntry> entry : metadataDictionary) {
      if (metadataFilter.doesMatch(entry.value.getMetadata())) {
        numRowsMatching += entry.value.size();
      }
    }
    return numRowsMatching;
  }

  public LongHashSet getMatchingRowNums(MetaFilter metadataFilter) {
    if (metadataFilter == null || metadataFilter.isEmpty()) {
      return rowNumsToLongHashSet(rowNumToMetadataDictionaryKeyMap);
    }
    LongHashSet matchingRowNums = new LongHashSet();
    for (LongObjectCursor<MetadataDictionaryEntry> entry : metadataDictionary) {
      if (metadataFilter.doesMatch(entry.value.getMetadata())) {
        addAll(matchingRowNums, entry.value.getRowNums());
      }
    }
    return matchingRowNums;
  }

  public PreFilteringResult getMatchingRowNumsIfUnderLimit(
      MetaFilter metadataFilter, int maxPreFilteringNumRows) {
    if (maxPreFilteringNumRows < 0) {
      throw new IllegalArgumentException("maxPreFilteringNumRows must be >= 0.");
    }
    if (metadataFilter == null || metadataFilter.isEmpty()) {
      if (rowNumToMetadataDictionaryKeyMap.size() > maxPreFilteringNumRows) {
        return PreFilteringResult.failure();
      }
      return PreFilteringResult.success(rowNumsToLongHashSet(rowNumToMetadataDictionaryKeyMap));
    }
    LongHashSet matchingRowNums = new LongHashSet();
    for (LongObjectCursor<MetadataDictionaryEntry> entry : metadataDictionary) {
      if (!metadataFilter.doesMatch(entry.value.getMetadata())) {
        continue;
      }
      addAll(matchingRowNums, entry.value.getRowNums());
      if (matchingRowNums.size() > maxPreFilteringNumRows) {
        return PreFilteringResult.failure();
      }
    }
    return PreFilteringResult.success(matchingRowNums);
  }

  public LongObjectHashMap<LongMeta> getAllMetadata() {
    LongObjectHashMap<LongMeta> rowNumToMetadataMap =
        new LongObjectHashMap<>(rowNumToMetadataDictionaryKeyMap.size());
    for (LongLongCursor entry : rowNumToMetadataDictionaryKeyMap) {
      MetadataDictionaryEntry metadataDictionaryEntry = metadataDictionary.get(entry.value);
      if (metadataDictionaryEntry != null) {
        rowNumToMetadataMap.put(entry.key, metadataDictionaryEntry.getMetadata());
      }
    }
    return rowNumToMetadataMap;
  }

  public int size() {
    return rowNumToMetadataDictionaryKeyMap.size();
  }

  public boolean isEmpty() {
    return rowNumToMetadataDictionaryKeyMap.isEmpty();
  }

  int getNumMetadataDictionaryEntries() {
    return metadataDictionary.size();
  }

  @Nullable
  private MetadataDictionaryEntry getMetadataDictionaryEntry(long rowNum) {
    if (!rowNumToMetadataDictionaryKeyMap.containsKey(rowNum)) {
      return null;
    }
    long metadataDictionaryKey = rowNumToMetadataDictionaryKeyMap.get(rowNum);
    return metadataDictionary.get(metadataDictionaryKey);
  }

  private static LongHashSet rowNumsToLongHashSet(LongLongHashMap rowNumMap) {
    LongHashSet rowNums = new LongHashSet(rowNumMap.size());
    for (LongLongCursor entry : rowNumMap) {
      rowNums.add(entry.key);
    }
    return rowNums;
  }

  private static void addAll(LongHashSet target, LongHashSet values) {
    for (LongCursor value : values) {
      target.add(value.value);
    }
  }
}
