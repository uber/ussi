/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import java.io.Serializable;
import java.util.Objects;

/**
 * A unit-cost, L1-boundable edit distance between two sequences, the distances that the
 * generalized Levenshtein distance generalizes over. Every one of them charges unit cost for
 * inserting or deleting one element, which bounds the normalized distance the comparators report
 * to [0.0, 1.0]; they differ in the other moves they permit, and so in
 * {@link #getL1BoundFactor()}.
 *
 * <p>That bound is what lets an inverted index generate candidates for an order-sensitive
 * distance. Two sequences within edit distance {@code d} have element multisets within L1 distance
 * {@code getL1BoundFactor() * d} of each other, so a candidate sharing too few elements with the
 * query, disregarding their order, cannot be close enough in order either.
 */
public abstract class SequenceDistance implements Serializable {

  /** Returned instead of a distance once the distance provably exceeds the caller's budget. */
  public static final long DISTANCE_EXCEEDED = -1L;

  /** Half of {@link Long#MAX_VALUE} so that adding a unit cost cannot overflow. */
  private static final long UNSET = Long.MAX_VALUE / 2;

  private final double l1BoundFactor;
  private final boolean allowsSubstitution;
  private final boolean allowsTransposition;

  protected SequenceDistance(
      double l1BoundFactor, boolean allowsSubstitution, boolean allowsTransposition) {
    if (l1BoundFactor <= 0.0) {
      throw new IllegalArgumentException(
          String.format("l1BoundFactor must be greater than 0.0, got %s.", l1BoundFactor));
    }
    this.l1BoundFactor = l1BoundFactor;
    this.allowsSubstitution = allowsSubstitution;
    this.allowsTransposition = allowsTransposition;
  }

  /**
   * Returns the factor bounding two sequences' element multisets' L1 distance by this edit
   * distance, so that {@code l1Distance <= getL1BoundFactor() * distance} for every pair.
   */
  public final double getL1BoundFactor() {
    return l1BoundFactor;
  }

  /**
   * Returns the distance between two sequences, or {@link #DISTANCE_EXCEEDED} once it provably
   * exceeds {@code maxDistance}. Work is confined to the {@code 2 * maxDistance + 1} diagonals
   * around the main diagonal, and stops early once a whole row exceeds the budget.
   */
  public final long getDistance(
      LongTermsAndValues sequence1, LongTermsAndValues sequence2, long maxDistance) {
    validateSequence(sequence1, "sequence1");
    validateSequence(sequence2, "sequence2");
    // The band's width is driven by the shorter sequence, so scan rows over the longer one.
    return sequence1.termsLength() <= sequence2.termsLength()
        ? getBandedDistance(sequence1, sequence2, maxDistance)
        : getBandedDistance(sequence2, sequence1, maxDistance);
  }

  /**
   * Runs the banded dynamic program with {@code shorter} along the row and {@code longer} down the
   * columns.
   *
   * <p>Only the band is stored, right-aligned: slot {@code index} of a row holds column {@code
   * lowColumn + index - 1}, and slot zero holds the column just left of the band. That keeps a row
   * {@code min(numColumns, 2 * maxDistance + 1)} slots wide. Because the band's first column
   * advances by one on most rows, a retained row has to slide one slot left to stay aligned; see
   * {@link #shiftLeft}.
   *
   * <p>The rows rotate by reference rather than being copied. Every cell read while filling a row
   * is either written earlier in that same row, sits in a retained row at a slot that row wrote,
   * or is one of the infinities fenced below, so no stale value is ever read.
   */
  private long getBandedDistance(
      LongTermsAndValues shorter, LongTermsAndValues longer, long requestedMaxDistance) {
    int numColumns = shorter.termsLength();
    int numRows = longer.termsLength();
    if (Math.abs(numRows - numColumns) > requestedMaxDistance) {
      return DISTANCE_EXCEEDED;
    }
    if (numRows == 0) {
      return numColumns;
    }
    if (numColumns == 0) {
      return numRows;
    }
    // A budget above numRows + numColumns rejects nothing, and capping stops the band width below
    // from overflowing on the unbounded budget a minSimilarity of zero produces.
    long maxDistance = Math.min(requestedMaxDistance, (long) numRows + numColumns);
    // Hoist the move rules into locals so the innermost loop tests a local rather than a field.
    boolean substitutes = allowsSubstitution;
    boolean transposes = allowsTransposition;

    // Two slots beyond the band itself: one for the column left of it, one to fence its right end.
    int bandWidth = (int) Math.min(numColumns, 2L * maxDistance + 1L);
    long[] previous = new long[bandWidth + 2];
    long[] current = new long[bandWidth + 2];
    long[] beforePrevious = transposes ? new long[bandWidth + 2] : null;

    // Row zero's band starts at column one, so its slots and its columns coincide.
    int lowColumn = 1;
    int cells = (int) Math.min(numColumns, maxDistance);
    for (int index = 0; index <= cells; ++index) {
      previous[index] = index;
    }
    previous[cells + 1] = UNSET;

    for (int row = 1; row <= numRows; ++row) {
      int previousLowColumn = lowColumn;
      lowColumn = (int) Math.max(1L, row - maxDistance);
      int highColumn = (int) Math.min(numColumns, row + maxDistance);
      cells = highColumn - lowColumn + 1;
      if (lowColumn == previousLowColumn) {
        current[0] = row;
      } else {
        shiftLeft(previous);
        if (transposes) {
          shiftLeft(beforePrevious);
        }
        // Every path through the column just left of the band is over budget for this row.
        current[0] = UNSET;
      }
      long rowMinimum = current[0];

      int index = 1;
      for (int column = lowColumn; column <= highColumn; ++column, ++index) {
        if (shorter.getTerm(column - 1) == longer.getTerm(row - 1)) {
          current[index] = previous[index - 1];
        } else {
          long best = Math.min(previous[index] + 1, current[index - 1] + 1);
          if (substitutes) {
            best = Math.min(best, previous[index - 1] + 1);
          }
          // Below slot two the transposed cell lies left of the band, over budget, so the move
          // cannot win; the same test keeps the two-back term reads in range.
          if (transposes
              && row >= 2
              && index >= 2
              && shorter.getTerm(column - 1) == longer.getTerm(row - 2)
              && shorter.getTerm(column - 2) == longer.getTerm(row - 1)) {
            best = Math.min(best, beforePrevious[index - 2] + 1);
          }
          current[index] = best;
        }
        rowMinimum = Math.min(rowMinimum, current[index]);
      }
      // The next row's band may reach one column further right, so fence the slot past this one's.
      // A leftover from an older row cannot change the distance, but it can understate a later
      // row's minimum and so cost the early exit below.
      current[cells + 1] = UNSET;
      if (rowMinimum > maxDistance) {
        return DISTANCE_EXCEEDED;
      }

      long[] retired = transposes ? beforePrevious : previous;
      if (transposes) {
        beforePrevious = previous;
      }
      previous = current;
      current = retired;
    }
    // The length check above admits only pairs whose last row reaches numColumns, which the
    // right-aligned layout puts in the last slot that row wrote.
    return previous[cells] <= maxDistance ? previous[cells] : DISTANCE_EXCEEDED;
  }

  /**
   * Slides a retained row one slot left, to line up with a band whose first column is one further
   * right than the band the row was filled against, and fences the vacated slot.
   */
  private static void shiftLeft(long[] row) {
    System.arraycopy(row, 1, row, 0, row.length - 1);
    row[row.length - 1] = UNSET;
  }

  /**
   * A record that carries values is a dense or sparse feature, not a sequence: its terms are
   * sorted and deduplicated, so reading them in order would measure the distance between two
   * sorted element sets rather than between the sequences.
   */
  private static void validateSequence(LongTermsAndValues termsAndValues, String name) {
    Objects.requireNonNull(termsAndValues, name);
    if (termsAndValues.valuesLength() != 0) {
      throw new IllegalArgumentException(
          String.format(
              "A sequence distance requires records without values, got %s = %s.",
              name, termsAndValues));
    }
  }
}
