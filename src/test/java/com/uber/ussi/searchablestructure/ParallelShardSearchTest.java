package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class ParallelShardSearchTest {
  private static final int ROWS_PER_SHARD = 5;

  /** Long enough that a search returning on a failure would return while a shard still ran. */
  private static final long SLOW_SHARD_MILLIS = 300;

  @Test
  void keepsTheNearestRowsAcrossEveryShard() {
    for (int numShards : new int[] {1, 2, 4, 7, 16, 48}) {
      String message = "numShards=" + numShards;
      for (int maxResults : new int[] {1, 3, 10}) {
        List<RowNumAndSimilarity> keptRowNums =
            ParallelShardSearch.search(
                numShards, maxResults, 0.0f, (shard, min) -> rowsOfShard(shard, numShards));

        assertEquals(
            nearestRowNumsOfAllShards(numShards, maxResults),
            similaritiesOf(keptRowNums),
            message + " maxResults=" + maxResults);
      }
    }
  }

  @Test
  void searchesEveryShardExactlyOnce() {
    for (int numShards : new int[] {4, 9, 16, 48}) {
      AtomicIntegerArray numSearchesByShard = new AtomicIntegerArray(numShards);

      ParallelShardSearch.search(
          numShards,
          ROWS_PER_SHARD,
          0.0f,
          (shard, min) -> {
            numSearchesByShard.incrementAndGet(shard);
            return rowsOfShard(shard, numShards);
          });

      for (int shard = 0; shard < numShards; shard++) {
        assertEquals(
            1, numSearchesByShard.get(shard), "numShards=" + numShards + " shard=" + shard);
      }
    }
  }

  @Test
  void handsOutEveryShardAtOnceEvenWhenTheBudgetIsOneThread() {
    // A pool of one thread runs one shard at a time whatever it is handed.
    assumeTrue(Runtime.getRuntime().availableProcessors() > 1, "needs more than one processor");
    int numShards = 4;
    AtomicInteger numShardsRunning = new AtomicInteger();
    AtomicInteger maxNumShardsRunningAtOnce = new AtomicInteger();
    ParallelismBudget.shared().update(Integer.MAX_VALUE);
    try {
      assertEquals(1, ParallelismBudget.shared().budget(), "the budget under this concurrency");

      ParallelShardSearch.search(
          numShards,
          ROWS_PER_SHARD,
          0.0f,
          (shard, min) -> {
            maxNumShardsRunningAtOnce.accumulateAndGet(
                numShardsRunning.incrementAndGet(), Math::max);
            try {
              Thread.sleep(SLOW_SHARD_MILLIS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            numShardsRunning.decrementAndGet();
            return rowsOfShard(shard, numShards);
          });
    } finally {
      ParallelismBudget.shared().update(1);
    }

    assertTrue(
        maxNumShardsRunningAtOnce.get() > 1,
        "ran " + maxNumShardsRunningAtOnce.get() + " shards at once under a budget of one thread");
  }

  @Test
  void holdsNothingWhenThereIsNoShardToSearch() {
    assertTrue(
        ParallelShardSearch.search(0, 10, 0.0f, (shard, min) -> rowsOfShard(shard, 1)).isEmpty());
  }

  @Test
  void throwsWhatAShardThrew() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                ParallelShardSearch.search(
                    8,
                    ROWS_PER_SHARD,
                    0.0f,
                    (shard, min) -> {
                      if (shard == 5) {
                        throw new IllegalStateException("shard five could not be searched");
                      }
                      return rowsOfShard(shard, 8);
                    }));

    assertEquals("shard five could not be searched", thrown.getMessage());
  }

  @Test
  void leavesNoShardStillRunningWhenAShardFails() {
    // The caller searches under a read lock that keeps writers off the shards, so a shard still
    // reading after the search has thrown would be reading a structure nothing is protecting. The
    // shard that fails does so at once while the others take their time, so a search that returned
    // on the failure would return while they were still reading.
    int numShards = 16;
    AtomicInteger numShardsRunning = new AtomicInteger();

    assertThrows(
        IllegalStateException.class,
        () ->
            ParallelShardSearch.search(
                numShards,
                ROWS_PER_SHARD,
                0.0f,
                (shard, min) -> {
                  if (shard == 1) {
                    throw new IllegalStateException("the first handed-off shard failed");
                  }
                  numShardsRunning.incrementAndGet();
                  try {
                    Thread.sleep(SLOW_SHARD_MILLIS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  numShardsRunning.decrementAndGet();
                  return rowsOfShard(shard, numShards);
                }));

    assertEquals(0, numShardsRunning.get(), "every shard finished before the search returned");
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

  /** The similarities a search of every shard in turn would have kept, nearest first. */
  private static List<Float> nearestRowNumsOfAllShards(int numShards, int maxResults) {
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
