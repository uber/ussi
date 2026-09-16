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
import com.uber.ussi.searchablestructure.SearchFanOut;
import com.uber.ussi.searchablestructure.index.Index;
import java.util.List;
import java.util.Objects;

/**
 * An inverted index holding its rows in shards, each with inverted lists of its own.
 *
 * <p>A search searches every shard and keeps the best rows across all of them, on as many threads
 * as the search may use. Shards divide the whole of a search, generation and verification alike,
 * which is why they are the one axis worth dividing a search along: dividing verification alone
 * reaches only the part of a search that scores candidates, and dividing the query's keys makes a
 * row appearing under several of them be visited once per part.
 *
 * <p>Shards also make a search cheaper before any thread is involved, because an inverted list
 * grows with the rows in its index and a search walks the lists of the query's keys. A shard
 * holding a fraction of the rows has lists shorter by that fraction, so the shards together do less
 * work than the whole would, and a search wins from sharding even given a single thread.
 *
 * <p>Against that, every shard carries a cost a search pays whatever its threads: a heap, a walk of
 * the query's keys, and a seek into each of their lists. That cost is per shard and does not shrink
 * with the shard, so it is what bounds the shard count from above and what {@link
 * #MIN_ROWS_PER_SHARD} keeps a small index away from.
 *
 * <p>Rows keep the row numbers they were inserted under. A shard holds a subset of the rows, never
 * a renumbering of them, so the rows this index returns are the ones its caller inserted and no
 * caller can tell the shards are there.
 */
public final class ShardedInvertedIndex extends Index {

  /**
   * Rows a shard must hold to be worth having. Below it the per-shard cost is a large share of what
   * searching the shard costs at all, and a search divided that finely spends more on its shards
   * than it saves on their lists.
   */
  public static final int MIN_ROWS_PER_SHARD = 50_000;

  private final Index[] shards;

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
      ShardBuilder shardBuilder) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    if (numShards <= 1) {
      throw new IllegalArgumentException("numShards must be greater than 1.");
    }
    Objects.requireNonNull(shardBuilder, "shardBuilder");
    LongObjectHashMap<LongTermsAndValues>[] rowsByShard = partitionRows(numShards);
    // Popularity is a property of the index, not of a shard: a term in a tenth of the index is in a
    // tenth of every shard, and a shard measuring it against its own rows would find the same
    // fraction of a tenth as many rows and discard nothing the index discards. Found once over all
    // the rows, the shards discard exactly what an unsharded index would, so sharding an index
    // cannot change what it finds.
    LongHashSet discardedTerms =
        BaseInvertedIndex.discardedTermsOf(namespaceConfig, rowNumToTermsAndValuesMap);
    this.shards = new Index[numShards];
    for (int shard = 0; shard < numShards; shard++) {
      // The metadata of every row is handed to each shard, which keeps the metadata of the rows it
      // was given, exactly as the hybrid index hands the same metadata to both of its halves.
      this.shards[shard] = shardBuilder.build(rowsByShard[shard], rowNumToMetaMap, discardedTerms);
    }
  }

  /**
   * Shards to hold {@code numRows} in: one per core once every shard can hold {@link
   * #MIN_ROWS_PER_SHARD} rows, and fewer while they cannot.
   *
   * <p>One per core is what lets a search with the cores to itself use all of them. A search
   * sharing the cores searches the same shards on fewer threads, which is why the count is not
   * taken from the load: the shards are fixed when the index is built, and a search under load
   * cannot rebuild them.
   */
  public static int numShardsFor(int numRows) {
    int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
    return Math.max(1, Math.min(cores, numRows / MIN_ROWS_PER_SHARD));
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return SearchFanOut.inWaves(
        shards.length,
        shardsAtOnce(),
        maxResults,
        shard ->
            shards[shard].size() == 0
                ? List.of()
                : shards[shard].getNearestNeighborRowNums(
                    maxResults, record, metadataFilter, minSimilarity));
  }

  @Override
  public List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return SearchFanOut.inWaves(
        shards.length,
        shardsAtOnce(),
        namespaceConfig.getMaxNumSimilarities(),
        shard ->
            shards[shard].size() == 0
                ? List.of()
                : shards[shard].getSimilarRowNums(minSimilarity, record, metadataFilter));
  }

  @Override
  protected void onRowDeleted(long rowNum) {
    shards[shardOf(rowNum, shards.length)].delete(rowNum);
  }

  @Override
  public void close() {
    for (Index shard : shards) {
      shard.close();
    }
  }

  int getNumShardsForTests() {
    return shards.length;
  }

  int getNumRowsInShardForTests(int shard) {
    return shards[shard].size();
  }

  /** The shard a row belongs to, which is where the row was put when the index was built. */
  private static int shardOf(long rowNum, int numShards) {
    return (int) Math.floorMod(rowNum, numShards);
  }

  private int shardsAtOnce() {
    return Math.max(1, Math.min(shards.length, searchParallelism()));
  }

  @SuppressWarnings("unchecked")
  private LongObjectHashMap<LongTermsAndValues>[] partitionRows(int numShards) {
    LongObjectHashMap<LongTermsAndValues>[] rowsByShard = new LongObjectHashMap[numShards];
    for (int shard = 0; shard < numShards; shard++) {
      rowsByShard[shard] = new LongObjectHashMap<>();
    }
    // Row numbers are handed out in turn, so taking them modulo the shard count divides the rows
    // evenly however many there are, which is what makes the shards cost the same to search.
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      rowsByShard[shardOf(entry.key, numShards)].put(entry.key, entry.value);
    }
    return rowsByShard;
  }
}
