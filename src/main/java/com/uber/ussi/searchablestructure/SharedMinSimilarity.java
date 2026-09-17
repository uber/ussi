/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The highest minimum similarity any division of a search has proved the answer will hold, shared
 * between its divisions.
 *
 * <p>A division that has filled its heap holds as many rows as the answer keeps, each scoring at
 * least
 * its weakest retained row. Those rows are among the answer's candidates, so the answer's weakest
 * kept score is at least that division's, and no row scoring below it can reach the answer. One
 * division's minimum similarity is therefore sound for every division, and this holds the highest
 * any of
 * them has published.
 *
 * <p>A division whose heap is not yet full has proved nothing and publishes nothing.
 *
 * <p>This only rises, and it is one value rather than a structure, so it needs no lock. Divisions
 * keep
 * their own heaps; nothing but this scalar is shared.
 */
public final class SharedMinSimilarity {

  private final AtomicInteger minSimilarityBits;

  public SharedMinSimilarity(float minSimilarity) {
    this.minSimilarityBits = new AtomicInteger(Float.floatToRawIntBits(minSimilarity));
  }

  /** The highest minimum similarity published so far. */
  public float get() {
    return Float.intBitsToFloat(minSimilarityBits.get());
  }

  /**
   * Publishes {@code minSimilarity}, which is ignored if a higher one has already been published.
   *
   * <p>Publishers race only with each other, and the accumulator retries for them: it re-reads and
   * re-applies until it writes, so the highest of them survives whatever order they arrive in.
   */
  public void raiseTo(float minSimilarity) {
    minSimilarityBits.accumulateAndGet(
        Float.floatToRawIntBits(minSimilarity),
        (publishedBits, candidateBits) ->
            Float.intBitsToFloat(candidateBits) > Float.intBitsToFloat(publishedBits)
                ? candidateBits
                : publishedBits);
  }
}
