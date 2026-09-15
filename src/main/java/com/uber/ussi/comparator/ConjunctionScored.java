/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

/**
 * A similarity that a conjunction determines, which a structure generating candidates by merging
 * inverted lists needs.
 *
 * <p>A conjunction is the part of the similarity the query and an indexed row derive from the keys
 * they share. It is the intersection of the two rows for Jaccard and Ruzicka, and the squared
 * distance over the shared keys for L2.
 *
 * <p>Merging walks the lists of the query's keys together, growing one row's conjunction a shared
 * key at a time. That is why a measure needs both halves of this interface: one to accumulate the
 * conjunction and turn a complete one into a similarity, and one to bound what a partial
 * conjunction can still reach, which is what lets the merge abandon a row before reading the rest
 * of its keys.
 */
public interface ConjunctionScored {

  /** Returns what one key the query and an indexed row share adds to the conjunction. */
  double conjunctionContribution(float value1, float value2);

  /** Returns the normalized similarity implied by a complete conjunction. */
  double similarityFromConjunction(
      double conjunction,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2);

  /**
   * Returns the highest normalized similarity still reachable from a partial conjunction, where
   * {@code unscannedKeysUniValue} bounds what the query's not-yet-merged keys can add.
   */
  double maxSimilarityFromPartialConjunction(
      double conjunction,
      double unscannedKeysUniValue,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2);

  /**
   * Returns whether a suffix of per-key bounds is a valid bound on what the query's unscanned keys
   * can still contribute to the conjunction. A measure that says no is given a looser bound, so
   * this costs candidates examined rather than candidates found.
   */
  boolean doesSuffixBoundConjunction();
}
