package com.uber.ussi.searchablestructure.index.inverted.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SharedFloorTest {

  @Test
  void holdsTheFloorItWasGivenUntilAHigherOneIsPublished() {
    SharedFloor floor = new SharedFloor(0.25f);

    assertEquals(0.25f, floor.get());

    floor.raiseTo(0.1f);
    assertEquals(0.25f, floor.get(), "a lower floor is ignored");

    floor.raiseTo(0.25f);
    assertEquals(0.25f, floor.get(), "an equal floor is ignored");

    floor.raiseTo(0.75f);
    assertEquals(0.75f, floor.get(), "a higher floor is published");
  }

  @Test
  void keepsTheHighestFloorWhenEveryShardPublishesAtOnce() throws Exception {
    int numShards = 16;
    SharedFloor floor = new SharedFloor(0.0f);
    CountDownLatch publishTogether = new CountDownLatch(1);
    List<Thread> shards = new ArrayList<>(numShards);
    for (int shard = 0; shard < numShards; shard++) {
      // Published lowest last, so a floor that took the last write rather than the highest would
      // end up holding a low one.
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
                floor.raiseTo(published);
              }));
    }
    shards.forEach(Thread::start);

    publishTogether.countDown();
    for (Thread shard : shards) {
      shard.join();
    }

    assertEquals(1.0f, floor.get());
  }
}
