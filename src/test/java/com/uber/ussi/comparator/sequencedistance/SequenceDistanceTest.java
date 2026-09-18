package com.uber.ussi.comparator.sequencedistance;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.sequencedistance.SequenceDistanceFactory.SequenceDistanceType;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.utils.MathUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SequenceDistanceTest {

  private static final long GENEROUS_BUDGET = 1_000L;

  private static final DistanceCase[] DISTANCE_CASES = {
    new DistanceCase("identical", SequenceDistanceType.LEVENSHTEIN, "abc", "abc", 0),
    new DistanceCase("one substitution", SequenceDistanceType.LEVENSHTEIN, "abc", "abd", 1),
    new DistanceCase("one insertion", SequenceDistanceType.LEVENSHTEIN, "abc", "abcd", 1),
    new DistanceCase("one deletion", SequenceDistanceType.LEVENSHTEIN, "abcd", "abc", 1),
    new DistanceCase(
        "classic kitten/sitting", SequenceDistanceType.LEVENSHTEIN, "kitten", "sitting", 3),
    new DistanceCase("empty against three", SequenceDistanceType.LEVENSHTEIN, "", "abc", 3),
    new DistanceCase("both empty", SequenceDistanceType.LEVENSHTEIN, "", "", 0),
    new DistanceCase("reversed pair", SequenceDistanceType.LEVENSHTEIN, "ab", "ba", 2),
    // Damerau-Levenshtein charges one adjacent transposition where Levenshtein charges two edits.
    new DistanceCase("transposition", SequenceDistanceType.DAMERAU_LEVENSHTEIN, "ab", "ba", 1),
    new DistanceCase(
        "transposition inside", SequenceDistanceType.DAMERAU_LEVENSHTEIN, "abcd", "abdc", 1),
    new DistanceCase(
        "no transposition helps", SequenceDistanceType.DAMERAU_LEVENSHTEIN, "abc", "abd", 1),
    // LCS forbids substitution, so a mismatch costs a delete plus an insert.
    new DistanceCase("substitution costs two", SequenceDistanceType.LCS, "abc", "abd", 2),
    new DistanceCase("pure insertion", SequenceDistanceType.LCS, "abc", "abcd", 1),
    new DistanceCase("identical", SequenceDistanceType.LCS, "abc", "abc", 0),
  };

  private static final BoundFactorCase[] BOUND_FACTOR_CASES = {
    // A substitution can move one term out of one multiset and another into the other.
    new BoundFactorCase(SequenceDistanceType.LEVENSHTEIN, 2.0),
    new BoundFactorCase(SequenceDistanceType.DAMERAU_LEVENSHTEIN, 2.0),
    // Without substitution each edit moves exactly one term, which halves the bound.
    new BoundFactorCase(SequenceDistanceType.LCS, 1.0),
  };

  @Test
  void distanceCases() {
    for (DistanceCase testCase : DISTANCE_CASES) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(testCase.type);

      assertEquals(
          testCase.expected,
          distance.getDistance(
              sequence(testCase.sequence1), sequence(testCase.sequence2), GENEROUS_BUDGET),
          testCase.label());
      assertEquals(
          testCase.expected,
          distance.getDistance(
              sequence(testCase.sequence2), sequence(testCase.sequence1), GENEROUS_BUDGET),
          testCase.label() + " (arguments swapped)");
    }
  }

  @Test
  void boundFactorCases() {
    for (BoundFactorCase testCase : BOUND_FACTOR_CASES) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(testCase.type);

      assertEquals(testCase.expected, distance.getL1BoundFactor(), testCase.type.name());
    }
  }

  /**
   * Each edit moves at most {@code l1BoundFactor} terms between the multisets, which is what
   * lets an inverted index over them generate candidates.
   */
  @Test
  void everyEditMovesAtMostTheBoundFactorManyTerms() {
    Random random = new Random(9_001L);
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      for (int trial = 0; trial < 400; ++trial) {
        long[] terms1 = randomTerms(random);
        long[] terms2 = randomTerms(random);
        long editDistance =
            distance.getDistance(sequence(terms1), sequence(terms2), GENEROUS_BUDGET);

        assertTrue(
            l1Distance(terms1, terms2)
                <= distance.getL1BoundFactor() * editDistance + EPSILON_9,
            String.format(
                "%s trial=%d: L1 distance %d exceeds %s * edit distance %d.",
                type,
                trial,
                l1Distance(terms1, terms2),
                distance.getL1BoundFactor(),
                editDistance));
      }
    }
  }

  @Test
  void distanceBeyondTheBudgetIsReportedAsExceeded() {
    SequenceDistance distance =
        SequenceDistanceFactory.createSequenceDistance(SequenceDistanceType.LEVENSHTEIN);
    LongTermsAndValues kitten = sequence("kitten");
    LongTermsAndValues sitting = sequence("sitting");

    assertEquals(3L, distance.getDistance(kitten, sitting, 3L), "budget of exactly 3");
    assertEquals(
        SequenceDistance.DISTANCE_EXCEEDED,
        distance.getDistance(kitten, sitting, 2L),
        "budget of 2");
  }

  /** A minimum similarity of zero produces a Long.MAX_VALUE budget, which must not overflow. */
  @Test
  void anUnboundedBudgetAdmitsEveryDistance() {
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      LongTermsAndValues kitten = sequence("kitten");
      LongTermsAndValues sitting = sequence("sitting");

      assertEquals(
          distance.getDistance(kitten, sitting, GENEROUS_BUDGET),
          distance.getDistance(kitten, sitting, Long.MAX_VALUE),
          type.name());
    }
  }

  /** The banded program must agree with a full unbanded table whenever the budget admits. */
  @Test
  void bandedDistanceMatchesTheFullTable() {
    Random random = new Random(4_242L);
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      for (int trial = 0; trial < 400; ++trial) {
        long[] terms1 = randomTerms(random);
        long[] terms2 = randomTerms(random);
        LongTermsAndValues sequence1 = sequence(terms1);
        LongTermsAndValues sequence2 = sequence(terms2);
        long expected = fullTableDistance(type, terms1, terms2);

        assertEquals(
            expected,
            distance.getDistance(sequence1, sequence2, GENEROUS_BUDGET),
            type + " trial=" + trial);
        assertEquals(
            expected,
            distance.getDistance(sequence1, sequence2, expected),
            type + " trial=" + trial + " at an exact budget");
        if (expected > 0) {
          assertEquals(
              SequenceDistance.DISTANCE_EXCEEDED,
              distance.getDistance(sequence1, sequence2, expected - 1),
              type + " trial=" + trial + " one below the exact budget");
        }
      }
    }
  }

  /**
   * Sequences far longer than the budget, where the band covers a sliver of the table. The tally
   * guards against drifting into the wide-band case.
   */
  @Test
  void narrowBandsMatchTheFullTable() {
    Random random = new Random(1_234L);
    int narrowTrials = 0;
    int totalTrials = 0;
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      for (int trial = 0; trial < 200; ++trial) {
        long[] terms1 = randomLongTerms(random);
        long[] terms2 = fewEditsAway(terms1, random);
        LongTermsAndValues sequence1 = sequence(terms1);
        LongTermsAndValues sequence2 = sequence(terms2);
        long expected = fullTableDistance(type, terms1, terms2);
        ++totalTrials;
        if (2L * expected + 1L < Math.min(terms1.length, terms2.length)) {
          ++narrowTrials;
        }

        assertEquals(
            expected,
            distance.getDistance(sequence1, sequence2, expected),
            type + " trial=" + trial + " at an exact budget");
        if (expected > 0) {
          assertEquals(
              SequenceDistance.DISTANCE_EXCEEDED,
              distance.getDistance(sequence1, sequence2, expected - 1),
              type + " trial=" + trial + " one below the exact budget");
        }
      }
    }

    assertTrue(
        narrowTrials > totalTrials * 3 / 4,
        String.format("Only %d of %d trials had a narrow band.", narrowTrials, totalTrials));
  }

  /** A record with values has sorted, deduplicated terms, so its order is not the sequence's. */
  @Test
  void recordsCarryingValuesAreRejected() {
    SequenceDistance distance =
        SequenceDistanceFactory.createSequenceDistance(SequenceDistanceType.LEVENSHTEIN);
    LongTermsAndValues sequence = sequence("abc");
    LongTermsAndValues sparse =
        LongTermsAndValuesTestFactory.create(new long[] {1L, 2L}, new float[] {1f, 1f}, 2.0);

    assertThrows(
        IllegalArgumentException.class,
        () -> distance.getDistance(sequence, sparse, GENEROUS_BUDGET));
    assertThrows(
        IllegalArgumentException.class,
        () -> distance.getDistance(sparse, sequence, GENEROUS_BUDGET));
  }

  @Test
  void anUnknownSequenceDistanceTypeIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SequenceDistanceFactory.createSequenceDistance("hamming"));
  }

  private static LongTermsAndValues sequence(String text) {
    return sequence(terms(text));
  }

  private static LongTermsAndValues sequence(long[] terms) {
    // A sequence's Uni value is its length, and it carries no values.
    return LongTermsAndValuesTestFactory.create(terms, new float[0], terms.length);
  }

  private static long[] randomTerms(Random random) {
    long[] terms = new long[random.nextInt(9)];
    for (int index = 0; index < terms.length; ++index) {
      terms[index] = random.nextInt(4);
    }
    return terms;
  }

  private static long[] randomLongTerms(Random random) {
    long[] terms = new long[40 + random.nextInt(160)];
    for (int index = 0; index < terms.length; ++index) {
      terms[index] = random.nextInt(26);
    }
    return terms;
  }

  /** Applies a handful of random edits, keeping the distance small next to the length. */
  private static long[] fewEditsAway(long[] terms, Random random) {
    List<Long> edited = new ArrayList<>(terms.length);
    for (long term : terms) {
      edited.add(term);
    }
    int numEdits = 1 + random.nextInt(5);
    for (int edit = 0; edit < numEdits; ++edit) {
      int position = random.nextInt(edited.size());
      switch (random.nextInt(4)) {
        case 0 -> edited.add(position, (long) random.nextInt(26));
        case 1 -> edited.remove(position);
        case 2 -> edited.set(position, (long) random.nextInt(26));
        default -> {
          if (position + 1 < edited.size()) {
            Collections.swap(edited, position, position + 1);
          }
        }
      }
    }
    long[] result = new long[edited.size()];
    for (int index = 0; index < result.length; ++index) {
      result[index] = edited.get(index);
    }
    return result;
  }

  /** The L1 distance between the two sequences' term multisets. */
  private static long l1Distance(long[] terms1, long[] terms2) {
    Map<Long, Integer> counts = new HashMap<>();
    for (long term : terms1) {
      counts.merge(term, 1, Integer::sum);
    }
    for (long term : terms2) {
      counts.merge(term, -1, Integer::sum);
    }
    long total = 0L;
    for (int count : counts.values()) {
      total += Math.abs(count);
    }
    return total;
  }

  /** Unbanded Wagner-Fischer reference, used only to check the banded implementation. */
  private static long fullTableDistance(
      SequenceDistanceType type, long[] sequence1, long[] sequence2) {
    int numRows = sequence1.length;
    int numColumns = sequence2.length;
    long[][] table = new long[numRows + 1][numColumns + 1];
    for (int row = 0; row <= numRows; ++row) {
      table[row][0] = row;
    }
    for (int column = 0; column <= numColumns; ++column) {
      table[0][column] = column;
    }
    boolean allowsSubstitution = type != SequenceDistanceType.LCS;
    boolean allowsTransposition = type == SequenceDistanceType.DAMERAU_LEVENSHTEIN;
    for (int row = 1; row <= numRows; ++row) {
      for (int column = 1; column <= numColumns; ++column) {
        if (sequence1[row - 1] == sequence2[column - 1]) {
          table[row][column] = table[row - 1][column - 1];
          continue;
        }
        long best = Math.min(table[row - 1][column] + 1, table[row][column - 1] + 1);
        if (allowsSubstitution) {
          best = Math.min(best, table[row - 1][column - 1] + 1);
        }
        if (allowsTransposition
            && row >= 2
            && column >= 2
            && sequence1[row - 1] == sequence2[column - 2]
            && sequence1[row - 2] == sequence2[column - 1]) {
          best = Math.min(best, table[row - 2][column - 2] + 1);
        }
        table[row][column] = best;
      }
    }
    return table[numRows][numColumns];
  }

  /**
   * The factor scales a distance budget into the L1 budget, so a factor at or below zero would
   * collapse every budget to zero.
   */
  @Test
  void anL1BoundFactorThatCannotScaleABudgetIsRejected() {
    for (double factor : new double[] {0.0, -1.0}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new FixedBoundFactorDistance(factor),
          "factor " + factor);
    }
    assertEquals(2.0, new FixedBoundFactorDistance(2.0).getL1BoundFactor(), EPSILON_9);
  }

  /** A distance that exists only to give the parent constructor an L1 bound factor to check. */
  private static final class FixedBoundFactorDistance extends SequenceDistance {
    private FixedBoundFactorDistance(double l1BoundFactor) {
      super(l1BoundFactor, /* allowsSubstitution */ true, /* allowsTransposition */ false);
    }
  }

  private static long[] terms(String text) {
    long[] terms = new long[text.length()];
    for (int index = 0; index < text.length(); ++index) {
      terms[index] = text.charAt(index);
    }
    return terms;
  }

  private record DistanceCase(
      String name, SequenceDistanceType type, String sequence1, String sequence2, long expected) {
    String label() {
      return type + " " + name + " (" + sequence1 + " -> " + sequence2 + ")";
    }
  }

  private record BoundFactorCase(SequenceDistanceType type, double expected) {}
}
