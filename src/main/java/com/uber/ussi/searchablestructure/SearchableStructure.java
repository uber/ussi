/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import java.util.List;

/** Common searchable structure API shared by caches and indexes. */
public interface SearchableStructure {

  boolean delete(long rowNum);

  LongObjectHashMap<LongTermsAndValues> getAll();

  List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter);

  List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);

  /**
   * Threads this structure may use to answer one search. Splitting a search across more than this
   * oversubscribes the machine once the other searches in flight are counted, which costs more in
   * tail latency than the split saves.
   *
   * <p>A structure whose thread count is a process-global setting cannot read this per search, since
   * the setting is shared by every search in flight; it registers with {@link
   * ParallelismBudget#onChange} instead.
   */
  default int searchParallelism() {
    return ParallelismBudget.shared().budget();
  }
}
