/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The weakest score any of a search's shards has proved the answer will hold, shared between them.
 *
 * <p>A shard that has filled its heap holds as many rows as the answer keeps, each scoring at least
 * its weakest retained row. Those rows are in the answer's candidates, so the answer's weakest kept
 * score is at least that shard's, and no row scoring below it can reach the answer. One shard's
 * floor is therefore sound for every shard, and this holds the highest any of them has published.
 *
 * <p>A shard whose heap is not yet full has proved nothing and publishes nothing.
 *
 * <p>The floor only rises, and it is one value rather than a structure, so it needs no lock: a
 * reader loads it, and a publisher raises it with a compare-and-set that retries only against
 * another publisher. Shards keep their own heaps; nothing but this scalar is shared.
 */
public final class SharedFloor {

  private final AtomicInteger floorBits;

  public SharedFloor(float floor) {
    this.floorBits = new AtomicInteger(Float.floatToRawIntBits(floor));
  }

  /** The highest floor published so far. */
  public float get() {
    return Float.intBitsToFloat(floorBits.get());
  }

  /** Publishes {@code floor}, which is ignored if the floor already stands at least that high. */
  public void raiseTo(float floor) {
    while (true) {
      int publishedBits = floorBits.get();
      if (floor <= Float.intBitsToFloat(publishedBits)) {
        return;
      }
      if (floorBits.compareAndSet(publishedBits, Float.floatToRawIntBits(floor))) {
        return;
      }
    }
  }
}
