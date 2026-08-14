/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.metadata;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.cursors.LongCursor;

/** Result of asking whether metadata pre-filtering can be used for a query. */
public final class PreFilteringResult {
  private static final PreFilteringResult FAILURE =
      new PreFilteringResult(/* success */ false, new LongHashSet());

  private final boolean success;
  private final LongHashSet rowNums;

  private PreFilteringResult(boolean success, LongHashSet rowNums) {
    this.success = success;
    this.rowNums = copyOf(rowNums);
  }

  public static PreFilteringResult success(LongHashSet rowNums) {
    return new PreFilteringResult(/* success */ true, rowNums);
  }

  public static PreFilteringResult failure() {
    return FAILURE;
  }

  public boolean isSuccess() {
    return success;
  }

  public LongHashSet getRowNums() {
    return copyOf(rowNums);
  }

  private static LongHashSet copyOf(LongHashSet rowNums) {
    LongHashSet copy = new LongHashSet(rowNums.size());
    for (LongCursor rowNum : rowNums) {
      copy.add(rowNum.value);
    }
    return copy;
  }
}
