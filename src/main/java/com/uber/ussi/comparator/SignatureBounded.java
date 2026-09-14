/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

/**
 * A similarity that can say how much of a record's signatures a qualifying candidate has to
 * collide with it on.
 *
 * <p>This is a property of the measure and not of any configuration: jaccard's signatures collide
 * at the Jaccard similarity whether or not a structure is keyed by them, and a comparator on a
 * scan satisfies this interface while generating no signatures at all. Which comparators can have
 * signatures generated for them is {@link ComparatorType#getSupportedSignatureGeneratorTypes}, and
 * generating them is a signature-keyed structure's job.
 */
public interface SignatureBounded {

  /**
   * Returns the smallest share of a record's signatures that a candidate clearing {@code
   * comparatorValue} can collide with it on, in [0.0, 1.0].
   *
   * <p>Signatures collide at a rate tracking the multiset similarity of the records they were
   * drawn from, so this is that similarity at the threshold, whatever the comparator itself
   * measures. A comparator measuring something else has to bound the multiset similarity from its
   * own threshold, and one whose threshold is not already a share of the record needs {@code
   * recordUniValue}, the Uni value over the record's own terms, to express one.
   *
   * <p>A share rather than a count is what lets a caller relax this by a generator's safety
   * margin: a concentration bound is stated on the similarity the generator estimates, so a margin
   * is only meaningful in the same units.
   */
  double getMinSharedSignatureFraction(double recordUniValue, double comparatorValue);
}
