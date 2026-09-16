package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class ShardFanOutTest {
  private static final int ROWS_PER_SHARD = 5;

  @Test
  void keepsTheBestRowsAcrossShardsHoweverManyRunAtOnce() {
    int[][] shardCountsAndWidths = {
      {1, 1}, {1, 8}, {2, 1}, {2, 2}, {4, 1}, {4, 2}, {4, 4}, {4, 8},
      {7, 3}, {16, 1}, {16, 5}, {16, 16}, {48, 6}, {48, 48},
    };
    for (int[] testCase : shardCountsAndWidths) {
      int numShards = testCase[0];
      int numShardsAtOnce = testCase[1];
      String message = "numShards=" + numShards + " numShardsAtOnce=" + numShardsAtOnce;
      for (int maxResults : new int[] {1, 3, 10}) {
        List<RowNumAndSimilarity> keptRows =
            ShardFanOut.search(
                numShards, numShardsAtOnce, maxResults, shard -> rowsOfShard(shard, numShards));

        assertEquals(
            bestRowsOfAllShards(numShards, maxResults),
            similaritiesOf(keptRows),
            message + " maxResults=" + maxResults);
      }
    }
  }

  @Test
  void searchesEveryShardExactlyOnce() {
    int[][] shardCountsAndWidths = {{4, 2}, {16, 5}, {16, 1}, {48, 6}, {9, 9}};
    for (int[] testCase : shardCountsAndWidths) {
      int numShards = testCase[0];
      int numShardsAtOnce = testCase[1];
      String message = "numShards=" + numShards + " numShardsAtOnce=" + numShardsAtOnce;
      AtomicIntegerArray numSearchesByShard = new AtomicIntegerArray(numShards);

      ShardFanOut.search(
          numShards,
          numShardsAtOnce,
          ROWS_PER_SHARD,
          shard -> {
            numSearchesByShard.incrementAndGet(shard);
            return rowsOfShard(shard, numShards);
          });

      for (int shard = 0; shard < numShards; shard++) {
        assertEquals(1, numSearchesByShard.get(shard), message + " shard=" + shard);
      }
    }
  }

  @Test
  void holdsNothingWhenThereIsNoShardToSearch() {
    assertTrue(ShardFanOut.search(0, 4, 10, shard -> rowsOfShard(shard, 1)).isEmpty());
  }

  @Test
  void throwsWhatAShardThrew() {
    for (int numShardsAtOnce : new int[] {1, 2, 8}) {
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () ->
                  ShardFanOut.search(
                      8,
                      numShardsAtOnce,
                      ROWS_PER_SHARD,
                      shard -> {
                        if (shard == 5) {
                          throw new IllegalStateException("shard five could not be searched");
                        }
                        return rowsOfShard(shard, 8);
                      }));

      assertEquals(
          "shard five could not be searched",
          thrown.getMessage(),
          "numShardsAtOnce=" + numShardsAtOnce);
    }
  }

  /** Rows of one shard, scored so that no two shards hold a row scoring the same. */
  private static List<RowNumAndSimilarity> rowsOfShard(int shard, int numShards) {
    List<RowNumAndSimilarity> rows = new ArrayList<>(ROWS_PER_SHARD);
    for (int row = 0; row < ROWS_PER_SHARD; row++) {
      long rowNum = (long) row * numShards + shard;
      rows.add(new RowNumAndSimilarity(rowNum, rowNum / 1000.0f));
    }
    return rows;
  }

  /** The similarities a search of every shard in turn would have kept, best first. */
  private static List<Float> bestRowsOfAllShards(int numShards, int maxResults) {
    List<Float> similarities = new ArrayList<>();
    for (int shard = 0; shard < numShards; shard++) {
      similarities.addAll(similaritiesOf(rowsOfShard(shard, numShards)));
    }
    similarities.sort(Comparator.<Float>naturalOrder().reversed());
    return similarities.subList(0, Math.min(maxResults, similarities.size()));
  }

  private static List<Float> similaritiesOf(List<RowNumAndSimilarity> rows) {
    List<Float> similarities = new ArrayList<>(rows.size());
    for (RowNumAndSimilarity row : rows) {
      similarities.add(row.getSimilarity());
    }
    similarities.sort(Comparator.<Float>naturalOrder().reversed());
    return similarities;
  }
}
