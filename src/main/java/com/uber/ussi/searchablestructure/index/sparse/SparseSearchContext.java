/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

/** The index state every sparse candidate generator reads while traversing inverted lists. */
interface SparseSearchContext {
  /** Returns the Uni value of an indexed row, by which every inverted list is sorted. */
  double getUniValue(long rowNum);
}
