/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.parallel.ParallelismBudget;
import java.util.List;

/** Common searchable structure API shared by caches and indexes. */
public interface SearchableStructure {

  boolean delete(long rowNum);

  LongObjectHashMap<LongTermsAndValues> getAll();

  /** The nearest {@code k} rows, however weakly they score. */
  default List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    return getNearestNeighborRowNums(k, record, metadataFilter, /* minSimilarity */ 0.0f);
  }

  /**
   * The nearest {@code k} rows scoring at least {@code minSimilarity}.
   *
   * <p>A caller searching several structures for one answer passes the weakest score it already
   * holds {@code k} of, since no row below that can reach the answer and this structure need not
   * score one. The floor only ever rises, so a structure may prune to it but must not return rows
   * beneath it.
   */
  List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity);

  List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);

  /**
   * Threads this structure may use to answer one search. Dividing a search across more than this
   * oversubscribes the machine once the other concurrent searches are counted, which costs more in
   * tail latency than the division saves.
   *
   * <p>A structure whose thread count is a process-global setting cannot read this per search,
   * since the setting is shared by every concurrent search. Such a structure registers with {@link
   * ParallelismBudget#onChange} instead.
   */
  default int getNumThreadsPerSearch() {
    return ParallelismBudget.shared().getNumThreadsPerSearch();
  }
}
