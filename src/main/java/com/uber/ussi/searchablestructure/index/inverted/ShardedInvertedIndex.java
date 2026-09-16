/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.ShardFanOut;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An inverted index holding its rows in shards, each an inverted index over a share of them.
 *
 * <p>A search searches every shard and keeps the best rows across all of them, as many shards at a
 * time as the thread budget allows. Rows are divided by row number modulo the shard count, which
 * divides them evenly because row numbers are handed out in turn, and evenly is what makes the
 * shards cost the same to search.
 *
 * <p>Shards divide the whole of a search rather than a phase of one, which is what makes them worth
 * dividing along. They also make a search cheaper before any thread is involved: an inverted list
 * grows with the rows in its index, so a shard holding a share of the rows has lists shorter by
 * that share, and the shards together score fewer candidates than the whole would. A sharded index
 * is therefore faster than an unsharded one even given a single thread.
 *
 * <p>Against that, every shard costs a search a heap, a walk of the query's keys and a seek into
 * each of their lists, whatever threads the search has. That cost is per shard and does not shrink
 * with the shard, which is what bounds the shard count.
 *
 * <p>Rows keep the row numbers they were inserted under. A shard holds a share of the rows and
 * never a renumbering of them, so no caller can tell the shards are there.
 */
public final class ShardedInvertedIndex extends Index {

  /**
   * Rows a shard holds once an index is divided as finely as it will be. It sizes the shard count
   * of a small index rather than deciding whether to shard one, so an index too small for a shard
   * per core takes as many shards as it has rows for and one when it has rows for one.
   *
   * <p>An index is built once from the rows it is given and never grows, so the count is settled at
   * build time and no index is ever converted from unsharded to sharded.
   */
  public static final int MIN_NUM_ROWS_PER_SHARD = 50_000;

  private final List<Index> shards;

  /** Builds one shard over the rows it holds, discarding the terms the index found popular. */
  public interface ShardBuilder {
    Index build(
        LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
        LongObjectHashMap<LongMeta> rowNumToMetaMap,
        LongHashSet discardedTerms);
  }

  public ShardedInvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      int numShards,
      IndexType indexType,
      ShardBuilder shardBuilder) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    if (numShards <= 1) {
      throw new IllegalArgumentException("numShards must be greater than 1.");
    }
    Objects.requireNonNull(shardBuilder, "shardBuilder");
    LongHashSet discardedTerms =
        discardedTermsOf(namespaceConfig, indexType, rowNumToTermsAndValuesMap);
    List<LongObjectHashMap<LongTermsAndValues>> rowsByShard = partitionRows(numShards);
    List<Index> builtShards = new ArrayList<>(numShards);
    for (int shard = 0; shard < numShards; shard++) {
      // Every shard is handed the metadata of every row and keeps that of the rows it was given,
      // exactly as the hybrid index hands the same metadata to both of its children.
      builtShards.add(shardBuilder.build(rowsByShard.get(shard), rowNumToMetaMap, discardedTerms));
    }
    this.shards = List.copyOf(builtShards);
  }

  /**
   * Shards to hold {@code numNumRows} in: one per core once every shard has {@link
   * #MIN_NUM_ROWS_PER_SHARD} rows to hold, and fewer while they have not.
   *
   * <p>One per core is what lets a search with the cores to itself use all of them. The count does
   * not follow the load, because the shards are fixed when the index is built and a search under
   * load searches the same shards on fewer threads.
   */
  public static int numShardsFor(int numRows) {
    int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
    return Math.max(1, Math.min(numCores, numRows / MIN_NUM_ROWS_PER_SHARD));
  }

  /**
   * The terms an inverted structure of {@code indexType} discards as popular, counted over all its
   * rows so that dividing the structure cannot change them.
   */
  static LongHashSet discardedTermsOf(
      NamespaceConfig namespaceConfig,
      IndexType indexType,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap) {
    return indexType == IndexType.INVERTED_HYBRID
        ? HybridIndex.discardedTermsOf(namespaceConfig, rowNumToTermsAndValuesMap)
        : BaseInvertedIndex.discardedTermsOf(namespaceConfig, rowNumToTermsAndValuesMap);
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return ShardFanOut.search(
        shards.size(),
        numShardsAtOnce(),
        maxResults,
        shard -> {
          Index index = shards.get(shard);
          return index.size() == 0
              ? List.of()
              : index.getNearestNeighborRowNums(maxResults, record, metadataFilter, minSimilarity);
        });
  }

  @Override
  public List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return ShardFanOut.search(
        shards.size(),
        numShardsAtOnce(),
        namespaceConfig.getMaxNumSimilarities(),
        shard -> {
          Index index = shards.get(shard);
          return index.size() == 0
              ? List.of()
              : index.getSimilarRowNums(minSimilarity, record, metadataFilter);
        });
  }

  @Override
  protected void onRowDeleted(long rowNum) {
    shards.get(shardOf(rowNum, shards.size())).delete(rowNum);
  }

  @Override
  public void close() {
    for (Index shard : shards) {
      shard.close();
    }
  }

  int getNumShardsForTests() {
    return shards.size();
  }

  int getNumRowsInShardForTests(int shard) {
    return shards.get(shard).size();
  }

  /** The shard a row belongs to, which is where the row was put when the index was built. */
  private static int shardOf(long rowNum, int numShards) {
    return Math.floorMod(rowNum, numShards);
  }

  private int numShardsAtOnce() {
    return Math.max(1, Math.min(shards.size(), searchParallelism()));
  }

  private List<LongObjectHashMap<LongTermsAndValues>> partitionRows(int numShards) {
    List<LongObjectHashMap<LongTermsAndValues>> rowsByShard = new ArrayList<>(numShards);
    for (int shard = 0; shard < numShards; shard++) {
      rowsByShard.add(new LongObjectHashMap<>());
    }
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      rowsByShard.get(shardOf(entry.key, numShards)).put(entry.key, entry.value);
    }
    return rowsByShard;
  }
}
