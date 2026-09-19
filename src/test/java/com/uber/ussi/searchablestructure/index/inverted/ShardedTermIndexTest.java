package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.ConfigKeys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** An index divided into shards answers what the same index undivided answers. */
class ShardedTermIndexTest {
  private static final float DELTA = 1e-6f;
  private static final int[] SHARD_COUNTS = {2, 3, 4, 8, 16, 37};

  @Test
  void answersWhatOneShardAnswers() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(400, 40);
    LongTermsAndValues query = jaccard(new long[] {1, 4, 9, 16, 25});
    TermIndex oneShard = new TermIndex(config(), rows, longObjectMap(), 1);

    for (int numShards : SHARD_COUNTS) {
      TermIndex sharded = new TermIndex(config(), rows, longObjectMap(), numShards);
      assertEquals(numShards, sharded.getNumShardsForTests());
      for (int k : new int[] {1, 5, 20}) {
        assertSameRows(
            oneShard.getNearestNeighborRowNums(k, query, MetaFilter.empty()),
            sharded.getNearestNeighborRowNums(k, query, MetaFilter.empty()),
            "k=" + k + " numShards=" + numShards);
      }
      assertSameRows(
          oneShard.getSimilarRowNums(0.1f, query, MetaFilter.empty()),
          sharded.getSimilarRowNums(0.1f, query, MetaFilter.empty()),
          "minimum similarity numShards=" + numShards);
    }
  }

  @Test
  void answersWhatOneShardAnswersUnderAFloor() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(300, 30);
    LongTermsAndValues query = jaccard(new long[] {2, 3, 5, 7});
    TermIndex oneShard = new TermIndex(config(), rows, longObjectMap(), 1);

    for (int numShards : new int[] {2, 5, 9}) {
      TermIndex sharded = new TermIndex(config(), rows, longObjectMap(), numShards);
      for (float floor : new float[] {0.0f, 0.05f, 0.2f, 0.9f}) {
        assertSameRows(
            oneShard.getNearestNeighborRowNums(10, query, MetaFilter.empty(), floor),
            sharded.getNearestNeighborRowNums(10, query, MetaFilter.empty(), floor),
            "floor=" + floor + " numShards=" + numShards);
      }
    }
  }

  @Test
  void answersWhatOneShardAnswersWhenPopularTermsAreDiscarded() {
    // Popularity is counted over the index's rows, not a shard's, so the shards discard what one
    // shard would and find what it finds.
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (long rowNum = 0; rowNum < 200; rowNum++) {
      long ownTerm = 100 + rowNum;
      rows.put(
          rowNum,
          rowNum % 10 == 0 ? jaccard(new long[] {ownTerm}) : jaccard(new long[] {1, ownTerm}));
    }
    NamespaceConfig config = config(Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5"));
    TermIndex oneShard = new TermIndex(config, rows, longObjectMap(), 1);

    for (int numShards : new int[] {2, 5, 10, 20}) {
      TermIndex sharded = new TermIndex(config, rows, longObjectMap(), numShards);
      for (long rowNum : new long[] {0, 1, 7, 150, 199}) {
        assertSameRows(
            oneShard.getNearestNeighborRowNums(5, rows.get(rowNum), MetaFilter.empty()),
            sharded.getNearestNeighborRowNums(5, rows.get(rowNum), MetaFilter.empty()),
            "numShards=" + numShards + " rowNum=" + rowNum);
      }
    }
  }

  @Test
  void holdsEveryRowsListsInExactlyOneShard() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(480, 20);

    for (int numShards : SHARD_COUNTS) {
      TermIndex sharded = new TermIndex(config(), rows, longObjectMap(), numShards);
      int rowsUnderTheCommonTerm = 0;
      for (int shard = 0; shard < numShards; shard++) {
        rowsUnderTheCommonTerm += sharded.getRowNumsForKeyForTests(shard, 1).length;
      }
      assertEquals(
          sharded.getRowNumsForKeyForTests(0, 1).length > 0 ? rowsUnderTheCommonTerm : 0,
          new TermIndex(config(), rows, longObjectMap(), 1).getRowNumsForKeyForTests(0, 1).length,
          "numShards=" + numShards);
      assertEquals(rows.size(), sharded.size(), "numShards=" + numShards);
    }
  }

  @Test
  void deleteRemovesTheRowFromEveryShardsAnswer() {
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(200, 20);
    LongTermsAndValues query = jaccard(new long[] {1, 4, 9, 16, 25});
    TermIndex sharded = new TermIndex(config(), rows, longObjectMap(), 8);
    long deleted =
        sharded.getNearestNeighborRowNums(5, query, MetaFilter.empty()).get(0).getRowNum();

    assertTrue(sharded.delete(deleted));
    assertFalse(sharded.delete(deleted), "a row already deleted is not deleted again");

    assertEquals(rows.size() - 1, sharded.size());
    for (RowNumAndSimilarity row :
        sharded.getNearestNeighborRowNums(5, query, MetaFilter.empty())) {
      assertFalse(row.getRowNum() == deleted, "the deleted row is not returned");
    }
    assertFalse(
        sharded.getAll().containsKey(deleted), "the deleted row is not returned for a rebuild");
  }

  @Test
  void takesAsManyShardsAsItHasRowsForUpToOnePerCore() {
    int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
    int minNumRows = BaseInvertedIndex.MIN_NUM_ROWS_PER_SHARD;

    assertEquals(1, BaseInvertedIndex.getNumShards(0));
    assertEquals(1, BaseInvertedIndex.getNumShards(minNumRows - 1));
    assertEquals(1, BaseInvertedIndex.getNumShards(minNumRows));
    assertEquals(2, BaseInvertedIndex.getNumShards(2 * minNumRows));
    assertEquals(numCores, BaseInvertedIndex.getNumShards(numCores * minNumRows));
    assertEquals(
        numCores,
        BaseInvertedIndex.getNumShards(1000 * numCores * minNumRows),
        "the shard count is bounded by the cores however many rows there are");
  }

  /** Rows numbered from zero, as the engine assigns them, so shards divide them in turn. */
  private static LongObjectHashMap<LongTermsAndValues> randomRows(int numRows, int vocabulary) {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    Random random = new Random(7);
    for (long rowNum = 0; rowNum < numRows; rowNum++) {
      int numTerms = 3 + random.nextInt(5);
      long[] terms = new long[numTerms];
      for (int term = 0; term < numTerms; term++) {
        terms[term] = 1 + random.nextInt(vocabulary);
      }
      rows.put(rowNum, jaccard(Arrays.stream(terms).distinct().sorted().toArray()));
    }
    return rows;
  }

  private static void assertSameRows(
      List<RowNumAndSimilarity> expected, List<RowNumAndSimilarity> actual, String message) {
    List<RowNumAndSimilarity> expectedOrdered = sortedNearestFirst(expected);
    List<RowNumAndSimilarity> actualOrdered = sortedNearestFirst(actual);
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

  private static List<RowNumAndSimilarity> sortedNearestFirst(List<RowNumAndSimilarity> rows) {
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
    Arrays.fill(values, 1.0f);
    return LongTermsAndValuesTestFactory.create(terms, values, terms.length);
  }
}
