package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SharedMinSimilarityTest {

  @Test
  void holdsWhatItWasGivenUntilAHigherOneIsPublished() {
    SharedMinSimilarity minSimilarity = new SharedMinSimilarity(0.25f);

    assertEquals(0.25f, minSimilarity.get());

    minSimilarity.raiseTo(0.1f);
    assertEquals(0.25f, minSimilarity.get(), "a lower one is ignored");

    minSimilarity.raiseTo(0.25f);
    assertEquals(0.25f, minSimilarity.get(), "an equal one is ignored");

    minSimilarity.raiseTo(0.75f);
    assertEquals(0.75f, minSimilarity.get(), "a higher one is published");
  }

  @Test
  void keepsTheHighestWhenEveryShardPublishesAtOnce() throws Exception {
    int numShards = 16;
    SharedMinSimilarity minSimilarity = new SharedMinSimilarity(0.0f);
    CountDownLatch publishTogether = new CountDownLatch(1);
    List<Thread> shards = new ArrayList<>(numShards);
    for (int shard = 0; shard < numShards; shard++) {
      // Published lowest last, so taking the last write rather than the highest would end up
      // holding a low one.
      float published = (numShards - shard) / (float) numShards;
      shards.add(
          new Thread(
              () -> {
                try {
                  publishTogether.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                minSimilarity.raiseTo(published);
              }));
    }
    shards.forEach(Thread::start);

    publishTogether.countDown();
    for (Thread shard : shards) {
      shard.join();
    }

    assertEquals(1.0f, minSimilarity.get());
  }
}
