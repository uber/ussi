/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bound a partially scanned pair of records can still reach, and the arguments it rejects.
 *
 * <p>The bound prunes a candidate before the rest of it is scanned, so an argument that does not
 * describe a partial scan would prune a row that qualifies.
 */
class BaseRuzickaComparatorTest {

  @Test
  void rejectsArgumentsThatDoNotDescribeAPartialScan() {
    // partialUni1, uni1, partialUni2, uni2, scannedIntersection, scannedUnion
    double[][] invalidArguments = {
      // Each quantity is a sum of absolute values, so none of them may be negative.
      {-1.0, 2.0, 1.0, 2.0, 0.5, 1.5},
      {1.0, -2.0, 1.0, 2.0, 0.5, 1.5},
      {1.0, 2.0, -1.0, 2.0, 0.5, 1.5},
      {1.0, 2.0, 1.0, -2.0, 0.5, 1.5},
      {1.0, 2.0, 1.0, 2.0, -0.5, 1.5},
      {1.0, 2.0, 1.0, 2.0, 0.5, -1.5},
      // What has been scanned of a record cannot exceed the whole of it.
      {3.0, 2.0, 1.0, 2.0, 0.5, 1.5},
      {1.0, 2.0, 3.0, 2.0, 0.5, 1.5},
      // An intersection cannot exceed the smaller of what was scanned of either record.
      {1.0, 2.0, 1.0, 2.0, 1.5, 1.5},
      // A union holds the intersection and no more than both records scanned.
      {1.0, 2.0, 1.0, 2.0, 0.5, 0.25},
      {1.0, 2.0, 1.0, 2.0, 0.5, 2.5},
    };
    for (double[] arguments : invalidArguments) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              BaseRuzickaComparator.computeMaxPossibleComparatorValue(
                  arguments[0], arguments[1], arguments[2], arguments[3], arguments[4],
                  arguments[5]),
          "arguments " + java.util.Arrays.toString(arguments));
    }
  }

  @Test
  void boundsWhatAPartiallyScannedPairCanStillReach() {
    // Half of each record scanned, sharing all of what was scanned. The unscanned halves may
    // intersect entirely, so the bound is one.
    assertEquals(
        1.0,
        BaseRuzickaComparator.computeMaxPossibleComparatorValue(1.0, 2.0, 1.0, 2.0, 1.0, 1.0),
        1e-12);

    // Nothing scanned so far intersects, so the bound is what the unscanned remainder can add
    // against the union already accumulated.
    double disjointSoFar =
        BaseRuzickaComparator.computeMaxPossibleComparatorValue(1.0, 2.0, 1.0, 2.0, 0.0, 2.0);

    assertTrue(disjointSoFar < 1.0, "a disjoint prefix cannot still reach one, got " + disjointSoFar);
    assertTrue(disjointSoFar >= 0.0, "a bound is never negative, got " + disjointSoFar);
  }

  /** A fully scanned pair has no remainder, so the bound is the similarity itself. */
  @Test
  void aFullyScannedPairIsBoundedByItsOwnSimilarity() {
    assertEquals(
        0.5,
        BaseRuzickaComparator.computeMaxPossibleComparatorValue(2.0, 2.0, 2.0, 2.0, 1.0, 2.0),
        1e-12);
  }
}
