package com.uber.ussi.sequencedistance;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.sequencedistance.SequenceDistanceFactory.SequenceDistanceType;
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
    // A substitution can move one element out of one multiset and another into the other.
    new BoundFactorCase(SequenceDistanceType.LEVENSHTEIN, 2.0),
    new BoundFactorCase(SequenceDistanceType.DAMERAU_LEVENSHTEIN, 2.0),
    // Without substitution each edit moves exactly one element, which halves the bound.
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
   * Each edit may move at most {@code l1BoundFactor} elements between the two multisets, which is
   * the property that lets an inverted index over those multisets generate candidates.
   */
  @Test
  void everyEditMovesAtMostTheBoundFactorManyElements() {
    Random random = new Random(9_001L);
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      for (int trial = 0; trial < 400; ++trial) {
        long[] elements1 = randomElements(random);
        long[] elements2 = randomElements(random);
        long editDistance =
            distance.getDistance(sequence(elements1), sequence(elements2), GENEROUS_BUDGET);

        assertTrue(
            l1Distance(elements1, elements2)
                <= distance.getL1BoundFactor() * editDistance + EPSILON_9,
            String.format(
                "%s trial=%d: L1 distance %d exceeds %s * edit distance %d.",
                type,
                trial,
                l1Distance(elements1, elements2),
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

  /**
   * A budget of Long.MAX_VALUE is what a similarity threshold of zero produces, and must not
   * overflow the band offsets.
   */
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
        long[] elements1 = randomElements(random);
        long[] elements2 = randomElements(random);
        LongTermsAndValues sequence1 = sequence(elements1);
        LongTermsAndValues sequence2 = sequence(elements2);
        long expected = fullTableDistance(type, elements1, elements2);

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
   * The regime the band exists for: sequences far longer than the budget, where the band covers a
   * sliver of the table and slides left on nearly every row. The tally guards against the test
   * quietly drifting into the wide-band case, where the band spans the table and never slides.
   */
  @Test
  void narrowBandsMatchTheFullTable() {
    Random random = new Random(1_234L);
    int narrowTrials = 0;
    int totalTrials = 0;
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      SequenceDistance distance = SequenceDistanceFactory.createSequenceDistance(type);
      for (int trial = 0; trial < 200; ++trial) {
        long[] elements1 = randomLongElements(random);
        long[] elements2 = fewEditsAway(elements1, random);
        LongTermsAndValues sequence1 = sequence(elements1);
        LongTermsAndValues sequence2 = sequence(elements2);
        long expected = fullTableDistance(type, elements1, elements2);
        ++totalTrials;
        if (2L * expected + 1L < Math.min(elements1.length, elements2.length)) {
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

  /**
   * A record carrying values has sorted, deduplicated terms, so reading them in order would
   * silently measure something other than the sequence distance.
   */
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
    return sequence(elements(text));
  }

  private static LongTermsAndValues sequence(long[] elements) {
    // A sequence's Uni value is its length, and it carries no values.
    return LongTermsAndValuesTestFactory.create(elements, new float[0], elements.length);
  }

  private static long[] randomElements(Random random) {
    long[] elements = new long[random.nextInt(9)];
    for (int index = 0; index < elements.length; ++index) {
      elements[index] = random.nextInt(4);
    }
    return elements;
  }

  private static long[] randomLongElements(Random random) {
    long[] elements = new long[40 + random.nextInt(160)];
    for (int index = 0; index < elements.length; ++index) {
      elements[index] = random.nextInt(26);
    }
    return elements;
  }

  /**
   * Copies a sequence and applies a handful of random edits, which keeps the distance small next to
   * the length however long the sequence is.
   */
  private static long[] fewEditsAway(long[] elements, Random random) {
    List<Long> edited = new ArrayList<>(elements.length);
    for (long element : elements) {
      edited.add(element);
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

  /** The L1 distance between the two sequences' element multisets. */
  private static long l1Distance(long[] elements1, long[] elements2) {
    Map<Long, Integer> counts = new HashMap<>();
    for (long element : elements1) {
      counts.merge(element, 1, Integer::sum);
    }
    for (long element : elements2) {
      counts.merge(element, -1, Integer::sum);
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
   * The factor scales a comparator's distance budget into the L1 budget candidate generation
   * prunes on, so a subclass declaring one at or below zero would collapse every budget to zero
   * and silently stop the index from generating candidates at all.
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

  /** A distance that exists only to hand the parent constructor an L1 bound factor to check. */
  private static final class FixedBoundFactorDistance extends SequenceDistance {
    private FixedBoundFactorDistance(double l1BoundFactor) {
      super(l1BoundFactor, /* allowsSubstitution */ true, /* allowsTransposition */ false);
    }
  }

  private static long[] elements(String text) {
    long[] elements = new long[text.length()];
    for (int index = 0; index < text.length(); ++index) {
      elements[index] = text.charAt(index);
    }
    return elements;
  }

  private record DistanceCase(
      String name, SequenceDistanceType type, String sequence1, String sequence2, long expected) {
    String label() {
      return type + " " + name + " (" + sequence1 + " -> " + sequence2 + ")";
    }
  }

  private record BoundFactorCase(SequenceDistanceType type, double expected) {}
}
