/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

/**
 * A similarity that can say how much of a record's keys a qualifying candidate has to share with
 * it, rather than only how many of them it may miss.
 *
 * <p>This is a property of the measure and not of any configuration: Jaccard's keys are shared at
 * the Jaccard similarity whether a structure keys by terms, by signatures, or is a scan keyed by
 * nothing at all. Which comparators can have signatures generated for them is {@link
 * ComparatorType#getSupportedSignatureGeneratorTypes}, and drawing them is a signature-keyed
 * structure's job.
 *
 * <p>Not every measure can implement this. L2 bounds no share of a record, two vectors being as
 * far apart as their magnitudes allow, and a structure keyed by signatures is refused it for that
 * reason.
 */
public interface KeyShareBounded {

  /**
   * Returns the smallest share of a record's keys that a candidate clearing {@code comparatorValue}
   * can share with it, in [0.0, 1.0].
   *
   * <p>Keys are shared at a rate tracking the multiset similarity of the records they were drawn
   * from, so this is that similarity at the threshold, whatever the comparator itself measures.
   * One fraction therefore serves both key spaces: signatures collide at it, since each stands for
   * one draw, and a record's own terms are shared in the same proportion. A comparator measuring
   * something else has to bound the multiset similarity from its own threshold, and one whose
   * threshold is not already a share of the record needs {@code recordUniValue}, the Uni value over
   * the record's own terms, to express one.
   *
   * <p>A share rather than a count is what lets a caller relax this by a generator's safety
   * margin: a concentration bound is stated on the similarity the generator estimates, so a margin
   * is only meaningful in the same units. It is also what lets {@link
   * Comparator#maxPrefixSumFromSharedFraction} turn it into a prefix over either key space.
   */
  double getMinSharedKeyFraction(double recordUniValue, double comparatorValue);
}
