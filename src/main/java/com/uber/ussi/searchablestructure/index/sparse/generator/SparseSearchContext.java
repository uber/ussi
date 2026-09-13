/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse.generator;

/**
 * The index state every sparse candidate generator reads while traversing inverted lists.
 *
 * <p>Public only so that the sparse indexes in the parent package can reach it. Nothing outside
 * this library's sparse implementation should depend on it.
 */
public interface SparseSearchContext {
  /** Returns the Uni value of an indexed row, by which every inverted list is sorted. */
  double getUniValue(long rowNum);
}
