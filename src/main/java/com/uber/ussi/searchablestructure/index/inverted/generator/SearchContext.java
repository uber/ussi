/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

/**
 * The index state every candidate generator reads while traversing inverted lists.
 *
 * <p>Public only for the sibling inverted index packages.
 */
public interface SearchContext {
  /** Returns the Uni value of an indexed row, by which every inverted list is sorted. */
  double getUniValue(long rowNum);
}
