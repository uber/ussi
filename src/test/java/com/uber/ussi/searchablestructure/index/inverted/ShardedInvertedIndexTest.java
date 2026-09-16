package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.utils.ConfigKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ShardedInvertedIndexTest {
  private static final float DELTA = 1e-6f;

  @Test
  void returnsWhatAnUnshardedIndexReturns() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(400, 40);
    LongTermsAndValues query = jaccard(new long[] {1, 4, 9, 16, 25});
    Index unsharded = new TermIndex(config(), rows, longObjectMap());

    for (int numShards : new int[] {2, 3, 4, 8, 16, 37}) {
      ShardedInvertedIndex sharded = sharded(rows, numShards);
      for (int k : new int[] {1, 5, 20}) {
        assertSameRows(
            unsharded.getNearestNeighborRowNums(k, query, MetaFilter.empty()),
            sharded.getNearestNeighborRowNums(k, query, MetaFilter.empty()),
            "k=" + k + " numShards=" + numShards);
      }
      assertSameRows(
          unsharded.getSimilarRowNums(0.1f, query, MetaFilter.empty()),
          sharded.getSimilarRowNums(0.1f, query, MetaFilter.empty()),
          "threshold numShards=" + numShards);
    }
  }

  @Test
  void returnsWhatAnUnshardedIndexReturnsUnderAFloor() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(300, 30);
    LongTermsAndValues query = jaccard(new long[] {2, 3, 5, 7});
    Index unsharded = new TermIndex(config(), rows, longObjectMap());

    for (int numShards : new int[] {2, 5, 9}) {
      ShardedInvertedIndex sharded = sharded(rows, numShards);
      for (float floor : new float[] {0.0f, 0.05f, 0.2f, 0.9f}) {
        assertSameRows(
            unsharded.getNearestNeighborRowNums(10, query, MetaFilter.empty(), floor),
            sharded.getNearestNeighborRowNums(10, query, MetaFilter.empty(), floor),
            "floor=" + floor + " numShards=" + numShards);
      }
    }
  }

  @Test
  void discardsTheTermsTheWholeIndexFindsPopularRatherThanAShardsOwn() {
    // Term 1 is in nine rows in ten and is popular in the index. Every other term is in one row in
    // ten. A shard of a tenth of the rows holds term 1 in nine rows of its ten, the same fraction,
    // but it holds every other term in its one row too, so a shard judging for itself would discard
    // all of them.
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (long rowNum = 0; rowNum < 200; rowNum++) {
      long ownTerm = 100 + rowNum;
      rows.put(
          rowNum,
          rowNum % 10 == 0
              ? jaccard(new long[] {ownTerm})
              : jaccard(new long[] {1, ownTerm}));
    }
    Map<String, String> halfTheRows = Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5");
    NamespaceConfig namespaceConfig = config(halfTheRows);
    TermIndex unsharded = new TermIndex(namespaceConfig, rows, longObjectMap());
    assertArrayEquals(
        new long[] {1},
        unsharded.getDiscardedTermsForTests(),
        "the popular term is the one in nine rows of ten");

    for (int numShards : new int[] {2, 5, 10, 20}) {
      ShardedInvertedIndex sharded = sharded(rows, numShards, halfTheRows);
      for (long rowNum : new long[] {0, 1, 7, 150, 199}) {
        LongTermsAndValues query = rows.get(rowNum);
        assertSameRows(
            unsharded.getNearestNeighborRowNums(5, query, MetaFilter.empty()),
            sharded.getNearestNeighborRowNums(5, query, MetaFilter.empty()),
            "numShards=" + numShards + " rowNum=" + rowNum);
      }
    }
  }

  @Test
  void holdsEveryRowInExactlyOneShard() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(500, 20);

    for (int numShards : new int[] {2, 4, 7, 16}) {
      ShardedInvertedIndex sharded = sharded(rows, numShards);
      assertEquals(numShards, sharded.getNumShardsForTests());
      int rowsAcrossShards = 0;
      for (int shard = 0; shard < numShards; shard++) {
        rowsAcrossShards += sharded.getNumRowsInShardForTests(shard);
      }
      assertEquals(rows.size(), rowsAcrossShards, "numShards=" + numShards);
      assertEquals(rows.size(), sharded.size(), "numShards=" + numShards);
    }
  }

  @Test
  void dividesRowsEvenlyBetweenShards() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(480, 20);
    ShardedInvertedIndex sharded = sharded(rows, 16);

    for (int shard = 0; shard < 16; shard++) {
      assertEquals(30, sharded.getNumRowsInShardForTests(shard), "shard=" + shard);
    }
  }

  @Test
  void deleteRemovesTheRowFromTheShardHoldingIt() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(200, 20);
    LongTermsAndValues query = jaccard(new long[] {1, 4, 9, 16, 25});
    ShardedInvertedIndex sharded = sharded(rows, 8);
    List<RowNumAndSimilarity> before =
        sharded.getNearestNeighborRowNums(5, query, MetaFilter.empty());

    long deleted = before.get(0).getRowNum();
    assertTrue(sharded.delete(deleted));
    assertFalse(sharded.delete(deleted), "a row already deleted is not deleted again");

    assertEquals(rows.size() - 1, sharded.size());
    for (RowNumAndSimilarity row :
        sharded.getNearestNeighborRowNums(5, query, MetaFilter.empty())) {
      assertFalse(row.getRowNum() == deleted, "the deleted row is not returned");
    }
    assertFalse(
        sharded.getAll().containsKey(deleted), "the deleted row is not handed on a rebuild");
  }

  @Test
  void deletingEveryRowLeavesNothingToFind() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(120, 15);
    ShardedInvertedIndex sharded = sharded(rows, 6);

    for (long rowNum = 0; rowNum < 120; rowNum++) {
      assertTrue(sharded.delete(rowNum), "rowNum=" + rowNum);
    }

    assertEquals(0, sharded.size());
    assertTrue(
        sharded
            .getNearestNeighborRowNums(10, jaccard(new long[] {1, 2, 3}), MetaFilter.empty())
            .isEmpty());
  }

  @Test
  void takesAsManyShardsAsItHasRowsForUpToOnePerCore() {
    int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
    int minNumRows = ShardedInvertedIndex.MIN_NUM_ROWS_PER_SHARD;

    assertEquals(1, ShardedInvertedIndex.numShardsFor(0));
    assertEquals(1, ShardedInvertedIndex.numShardsFor(minNumRows - 1));
    assertEquals(1, ShardedInvertedIndex.numShardsFor(minNumRows));
    assertEquals(2, ShardedInvertedIndex.numShardsFor(2 * minNumRows));
    assertEquals(numCores, ShardedInvertedIndex.numShardsFor(numCores * minNumRows));
    assertEquals(
        numCores,
        ShardedInvertedIndex.numShardsFor(1000 * numCores * minNumRows),
        "the shard count is bounded by the cores however many rows there are");
  }

  @Test
  void rejectsAShardCountThatWouldNotShard() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(10, 5);

    for (int numShards : new int[] {-1, 0, 1}) {
      assertThrows(IllegalArgumentException.class, () -> sharded(rows, numShards));
    }
  }

  @Test
  void rejectsSearchesTheUnshardedIndexWouldReject() {
    ShardedInvertedIndex sharded = sharded(randomRows(60, 10), 4);
    LongTermsAndValues query = jaccard(new long[] {1, 2});

    assertThrows(
        IllegalArgumentException.class,
        () -> sharded.getNearestNeighborRowNums(0, query, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> sharded.getSimilarRowNums(1.5f, query, MetaFilter.empty()));
  }

  private static ShardedInvertedIndex sharded(
      LongObjectHashMap<LongTermsAndValues> rows, int numShards) {
    return sharded(rows, numShards, Map.of());
  }

  private static ShardedInvertedIndex sharded(
      LongObjectHashMap<LongTermsAndValues> rows, int numShards, Map<String, String> indexParams) {
    NamespaceConfig namespaceConfig = config(indexParams);
    return new ShardedInvertedIndex(
        namespaceConfig,
        rows,
        longObjectMap(),
        numShards,
        IndexType.INVERTED_TERM,
        (shardRows, shardMetadata, discardedTerms) ->
            new TermIndex(namespaceConfig, shardRows, shardMetadata, discardedTerms));
  }

  /** Rows numbered from zero, as the engine hands them out, so shards divide them in turn. */
  private static LongObjectHashMap<LongTermsAndValues> randomRows(int numRows, int vocabulary) {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    Random random = new Random(7);
    for (long rowNum = 0; rowNum < numRows; rowNum++) {
      int numTerms = 3 + random.nextInt(5);
      long[] terms = new long[numTerms];
      for (int term = 0; term < numTerms; term++) {
        terms[term] = 1 + random.nextInt(vocabulary);
      }
      rows.put(rowNum, jaccard(distinctSorted(terms)));
    }
    return rows;
  }

  private static long[] distinctSorted(long[] terms) {
    return java.util.Arrays.stream(terms).distinct().sorted().toArray();
  }

  private static void assertSameRows(
      List<RowNumAndSimilarity> expected, List<RowNumAndSimilarity> actual, String message) {
    List<RowNumAndSimilarity> expectedOrdered = sortedByScore(expected);
    List<RowNumAndSimilarity> actualOrdered = sortedByScore(actual);
    assertEquals(expectedOrdered.size(), actualOrdered.size(), message);
    for (int row = 0; row < expectedOrdered.size(); row++) {
      assertEquals(
          expectedOrdered.get(row).getSimilarity(),
          actualOrdered.get(row).getSimilarity(),
          DELTA,
          message + " row=" + row);
      assertEquals(
          expectedOrdered.get(row).getRowNum(),
          actualOrdered.get(row).getRowNum(),
          message + " row=" + row);
    }
  }

  private static List<RowNumAndSimilarity> sortedByScore(List<RowNumAndSimilarity> rows) {
    List<RowNumAndSimilarity> sorted = new ArrayList<>(rows);
    sorted.sort(RowNumAndSimilarity.NEAREST_FIRST);
    return sorted;
  }

  private static NamespaceConfig config() {
    return config(Map.of());
  }

  private static NamespaceConfig config(Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_term")
        .indexParams(indexParams)
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues jaccard(long[] terms) {
    float[] values = new float[terms.length];
    java.util.Arrays.fill(values, 1.0f);
    return LongTermsAndValuesTestFactory.create(terms, values, terms.length);
  }
}
