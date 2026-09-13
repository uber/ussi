/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

/**
 * The index state every sparse candidate generator reads while traversing inverted lists.
 *
 * <p>Public only so that the inverted indexes in the sibling packages can reach it. Nothing outside
 * this library's inverted implementation should depend on it.
 */
public interface SearchContext {
  /** Returns the Uni value of an indexed row, by which every inverted list is sorted. */
  double getUniValue(long rowNum);
}
