/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.metadata;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.entity.meta.LongMeta;
import java.util.Objects;

/** One metadata-dictionary value and the rowNums sharing that exact metadata. */
final class MetadataDictionaryEntry {
  private final LongMeta metadata;
  private final LongHashSet rowNums;

  MetadataDictionaryEntry(LongMeta metadata) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.rowNums = new LongHashSet();
  }

  LongMeta getMetadata() {
    return metadata;
  }

  boolean addRowNum(long rowNum) {
    return rowNums.add(rowNum);
  }

  boolean removeRowNum(long rowNum) {
    return rowNums.remove(rowNum);
  }

  LongHashSet getRowNums() {
    LongHashSet copy = new LongHashSet(rowNums.size());
    for (LongCursor rowNum : rowNums) {
      copy.add(rowNum.value);
    }
    return copy;
  }

  int size() {
    return rowNums.size();
  }

  boolean isEmpty() {
    return rowNums.isEmpty();
  }
}
