/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;

/**
 * One complete search per shard of a structure, run at the same time, keeping the nearest rows
 * across all of them.
 *
 * <p>Every shard is searched. A structure's rows are divided between its shards, so a shard left
 * out would take its rows out of the answer. This is the counterpart of {@link ParallelRowScan},
 * which divides the rows of a single structure between threads rather than searching several
 * structures of rows at once.
 *
 * <p>Each shard search is complete in itself, with its own heap and its own tightened minimum
 * similarity, so each prunes from the rows it has seen rather than from the answer as a whole.
 *
 * <p>This returns only once every shard it submitted has finished. Callers search under a read
 * lock that excludes writers from the shards for exactly as long as the search holds it. A
 * submitted shard outliving the call could therefore read a structure a writer had begun to
 * change, or an index that consolidation had closed.
 */
public final class ParallelShardSearch {

  private ParallelShardSearch() {}

  /**
   * The nearest {@code maxResults} rows across {@code numShards} shards, each searched against a
   * minimum similarity the shards share and seeded at {@code minSimilarity}.
   */
  public static List<RowNumAndSimilarity> search(
      int numShards,
      int maxResults,
      float minSimilarity,
      WorkUnitSearcher searchShard) {
    return ParallelSearch.searchAndMerge(numShards, maxResults, minSimilarity, searchShard);
  }
}
