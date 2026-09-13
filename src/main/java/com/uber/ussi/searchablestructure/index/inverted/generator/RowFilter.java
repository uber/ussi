/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import com.uber.ussi.entity.meta.MetaFilter;
import javax.annotation.Nullable;

/**
 * Decides whether a generated candidate row is eligible for scoring.
 *
 * <p>Public only so that the inverted indexes in the sibling packages can reach it. Nothing outside
 * this library's inverted implementation should depend on it.
 */
@FunctionalInterface
public interface RowFilter {
  boolean canScore(long rowNum, @Nullable MetaFilter metadataFilter);
}
