/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.uber.ussi.entity.meta.MetaFilter;
import javax.annotation.Nullable;

/** Decides whether a generated candidate row is eligible for scoring. */
@FunctionalInterface
interface SparseSearchRowFilter {
  boolean canScore(long rowNum, @Nullable MetaFilter metadataFilter);
}
