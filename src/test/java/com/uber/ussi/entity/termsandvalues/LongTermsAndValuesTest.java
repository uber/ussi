package com.uber.ussi.entity.termsandvalues;

import static com.uber.ussi.TestLongObjectMaps.longHashSet;
import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparatornormalizer.ComplementComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LongTermsAndValuesTest {

  @Test
  void accessorsReturnStoredData() {
    LongTermsAndValues record =
        new LongTermsAndValues(new long[] {1L, 2L}, new float[] {3f, 4f}, 5.0);

    assertArrayEquals(new long[] {1L, 2L}, record.getTerms());
    assertArrayEquals(new float[] {3f, 4f}, record.getValues());
    assertEquals(1L, record.getTerm(0));
    assertEquals(4f, record.getValue(1));
    assertEquals(2, record.termsLength());
    assertEquals(2, record.valuesLength());
    assertEquals(5.0, record.getUniValue(), EPSILON_9);
  }

  @Test
  void nullArraysBecomeEmpty() {
    LongTermsAndValues record = new LongTermsAndValues(null, null, 0.0);

    assertEquals(0, record.termsLength());
    assertEquals(0, record.valuesLength());
  }

  @Test
  void newWithoutTermsPreservesAlignmentAndRecomputesUniValue() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record =
        new LongTermsAndValues(new long[] {1, 2, 3}, new float[] {1, 2, 3}, 14.0);

    LongTermsAndValues filtered = record.newWithoutTerms(longHashSet(2), comparator);

    assertArrayEquals(new long[] {1, 3}, filtered.getTerms());
    assertArrayEquals(new float[] {1, 3}, filtered.getValues());
    assertEquals(10.0, filtered.getUniValue(), EPSILON_9);
  }

  @Test
  void newWithoutTermsReturnsSameRecordWhenNothingIsRemoved() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);

    assertSame(record, record.newWithoutTerms(new LongHashSet(), comparator));
    assertSame(record, record.newWithoutTerms(longHashSet(2), comparator));
  }

  @Test
  void newWithoutTermsCanRemoveEveryTermAndSupportsSequences() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);
    LongTermsAndValues sequence = new LongTermsAndValues(new long[] {1, 2}, new float[0], 0.0);

    LongTermsAndValues emptySparse = sparse.newWithoutTerms(longHashSet(1), comparator);
    LongTermsAndValues filteredSequence = sequence.newWithoutTerms(longHashSet(1), comparator);

    assertEquals(0, emptySparse.termsLength());
    assertEquals(0, emptySparse.valuesLength());
    assertEquals(0.0, emptySparse.getUniValue(), EPSILON_9);
    assertArrayEquals(new long[] {2}, filteredSequence.getTerms());
    assertEquals(0, filteredSequence.valuesLength());
  }

  @Test
  void toTermMultisetSortsAndCountsTheTerms() {
    Comparator comparator =
        ComparatorFactory.createComparator("ngld", Map.of(), new ComplementComparatorNormalizer());
    LongTermsAndValues sequence =
        new LongTermsAndValues(new long[] {5, 2, 5, 9, 2, 5}, new float[0], 6.0);

    LongTermsAndValues multiset = sequence.toTermMultiset(comparator);

    assertArrayEquals(new long[] {2, 5, 9}, multiset.getTerms());
    assertArrayEquals(new float[] {2.0f, 3.0f, 1.0f}, multiset.getValues());
    // The counts sum to the sequence length, so both forms report one Uni value.
    assertEquals(sequence.getUniValue(), multiset.getUniValue(), EPSILON_9);

    // One term repeated throughout is the longest a run of equal terms can get.
    LongTermsAndValues repeated =
        new LongTermsAndValues(new long[] {4, 4, 4, 4}, new float[0], 4.0)
            .toTermMultiset(comparator);
    assertArrayEquals(new long[] {4}, repeated.getTerms());
    assertArrayEquals(new float[] {4.0f}, repeated.getValues());

    // A lone term is the shortest, and has to land in the arrays just the same.
    LongTermsAndValues single =
        new LongTermsAndValues(new long[] {6}, new float[0], 1.0).toTermMultiset(comparator);
    assertArrayEquals(new long[] {6}, single.getTerms());
    assertArrayEquals(new float[] {1.0f}, single.getValues());
  }

  @Test
  void toTermMultisetRejectsARecordCarryingValues() {
    Comparator comparator =
        ComparatorFactory.createComparator("ngld", Map.of(), new ComplementComparatorNormalizer());
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1, 2}, new float[] {1, 1}, 2.0);

    assertThrows(IllegalArgumentException.class, () -> sparse.toTermMultiset(comparator));
    assertThrows(NullPointerException.class, () -> sparse.toTermMultiset(null));
  }

  @Test
  void newWithoutTermsRejectsNullArguments() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);

    assertThrows(NullPointerException.class, () -> record.newWithoutTerms(null, comparator));
    assertThrows(
        NullPointerException.class, () -> record.newWithoutTerms(new LongHashSet(), null));
  }

  @Test
  void sharesAnyTermDetectsSortedTermIntersections() {
    LongTermsAndValues record =
        new LongTermsAndValues(new long[] {1, 4, 7}, new float[] {1, 1, 1}, 3.0);

    assertTrue(record.sharesAnyTerm(new LongTermsAndValues(new long[] {4}, new float[] {1}, 1.0)));
    assertTrue(
        record.sharesAnyTerm(
            new LongTermsAndValues(new long[] {2, 3, 7}, new float[] {1, 1, 1}, 3.0)));
    assertFalse(
        record.sharesAnyTerm(
            new LongTermsAndValues(new long[] {2, 5, 8}, new float[] {1, 1, 1}, 3.0)));
  }

  @Test
  void sharesAnyTermIsFalseForRecordsWithoutTermsAndRejectsNull() {
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1}, new float[] {1}, 1.0);
    LongTermsAndValues dense = new LongTermsAndValues(new long[0], new float[] {1}, 1.0);

    assertFalse(sparse.sharesAnyTerm(dense));
    assertFalse(dense.sharesAnyTerm(sparse));
    assertFalse(dense.sharesAnyTerm(dense));
    assertThrows(NullPointerException.class, () -> sparse.sharesAnyTerm(null));
  }

  @Test
  void mismatchedTermAndValueLengthsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LongTermsAndValues(new long[] {1L, 2L}, new float[] {3f}, 0.0));
  }

  @Test
  void rawConstructorIsPackagePrivate() throws NoSuchMethodException {
    int modifiers =
        LongTermsAndValues.class
            .getDeclaredConstructor(long[].class, float[].class, double.class)
            .getModifiers();

    assertFalse(Modifier.isPublic(modifiers));
    assertFalse(Modifier.isProtected(modifiers));
    assertFalse(Modifier.isPrivate(modifiers));
  }

  @Test
  void fromEncodesTermsUsingEncoder() {
    TermsAndValues source = new TermsAndValues(new String[] {"a", "bb"}, new float[] {1f, 2f});
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());

    LongTermsAndValues record = LongTermsAndValues.from(source, term -> term.length(), comparator);

    assertArrayEquals(new long[] {1L, 2L}, record.getTerms());
    assertArrayEquals(new float[] {1f, 2f}, record.getValues());
    assertEquals(5.0, record.getUniValue(), EPSILON_9);
  }

  @Test
  void fromCanonicalizesSparseTermsBeforeComputingUniValue() {
    TermsAndValues source =
        new TermsAndValues(new String[] {"b", "a", "a", "c"}, new float[] {3f, 1f, 2f, 0f});
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());

    LongTermsAndValues record =
        LongTermsAndValues.from(source, term -> term.charAt(0) - 'a' + 1L, comparator);

    assertArrayEquals(new long[] {1L, 2L}, record.getTerms());
    assertArrayEquals(new float[] {3f, 3f}, record.getValues());
    assertEquals(18.0, record.getUniValue(), EPSILON_9);
  }

  @Test
  void fromKeepsSortedUniqueTermsWhenAllAggregatedValuesAreZero() {
    TermsAndValues source =
        new TermsAndValues(new String[] {"b", "a", "a"}, new float[] {0f, 1f, -1f});
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());

    LongTermsAndValues record =
        LongTermsAndValues.from(source, term -> term.charAt(0) - 'a' + 1L, comparator);

    assertArrayEquals(new long[] {1L, 2L}, record.getTerms());
    assertArrayEquals(new float[] {0f, 0f}, record.getValues());
    assertEquals(0.0, record.getUniValue(), EPSILON_9);
  }

  @Test
  void validateComparablePairAcceptsDifferentSparseLengths() {
    LongTermsAndValues first = new LongTermsAndValues(new long[] {1L}, new float[] {1f}, 1.0);
    LongTermsAndValues second =
        new LongTermsAndValues(new long[] {1L, 2L}, new float[] {1f, 1f}, 2.0);

    LongTermsAndValues.validateComparablePair(first, second);
  }

  @Test
  void validateComparablePairRejectsMixedRecordTypes() {
    LongTermsAndValues dense = new LongTermsAndValues(new long[0], new float[] {1f}, 1.0);
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1L}, new float[] {1f}, 1.0);
    LongTermsAndValues sequence = new LongTermsAndValues(new long[] {1L}, new float[0], 1.0);

    assertThrows(
        ArraysSizeMismatchError.class,
        () -> LongTermsAndValues.validateComparablePair(dense, sparse));
    assertThrows(
        ArraysSizeMismatchError.class,
        () -> LongTermsAndValues.validateComparablePair(sequence, sparse));
    assertThrows(
        ArraysSizeMismatchError.class,
        () -> LongTermsAndValues.validateComparablePair(dense, sequence));
  }

  @Test
  void validateComparablePairAcceptsSequencesOfDifferentLengths() {
    LongTermsAndValues sequence =
        new LongTermsAndValues(new long[] {1L, 2L, 1L}, new float[0], 3.0);
    LongTermsAndValues longerSequence =
        new LongTermsAndValues(new long[] {2L, 1L, 1L, 2L}, new float[0], 4.0);
    LongTermsAndValues emptySequence = new LongTermsAndValues(new long[0], new float[0], 0.0);

    LongTermsAndValues.validateComparablePair(sequence, longerSequence);
    // An empty sequence is a legitimate comparand: every term of the other one is an insertion.
    LongTermsAndValues.validateComparablePair(sequence, emptySequence);
  }

  @Test
  void validateComparablePairRejectsRecordsWithNeitherTermsNorValues() {
    LongTermsAndValues empty = new LongTermsAndValues(new long[0], new float[0], 0.0);

    assertThrows(
        IllegalArgumentException.class,
        () -> LongTermsAndValues.validateComparablePair(empty, empty));
  }

  @Test
  void fromRejectsNullTermsAndValues() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());

    assertThrows(
        NullPointerException.class, () -> LongTermsAndValues.from(null, term -> 0L, comparator));
  }

  @Test
  void fromRejectsNullEncoder() {
    TermsAndValues source = new TermsAndValues(new String[] {"a"}, new float[] {1f});
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());

    assertThrows(
        NullPointerException.class, () -> LongTermsAndValues.from(source, null, comparator));
  }

  @Test
  void comparatorAwareFromRejectsNullComparator() {
    TermsAndValues source = new TermsAndValues(new String[] {"a"}, new float[] {1f});

    assertThrows(
        NullPointerException.class,
        () -> LongTermsAndValues.from(source, term -> 1L, (Comparator) null));
  }

  @Test
  void equalsAndHashCodeUseContents() {
    LongTermsAndValues first = new LongTermsAndValues(new long[] {1L}, new float[] {2f}, 3.0);
    LongTermsAndValues second = new LongTermsAndValues(new long[] {1L}, new float[] {2f}, 3.0);
    LongTermsAndValues different = new LongTermsAndValues(new long[] {1L}, new float[] {2f}, 4.0);

    assertEquals(first, second);
    assertEquals(first, first);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, different);
    assertNotEquals(first, "not-a-record");
  }

  @Test
  void toStringIncludesUniValue() {
    String text = new LongTermsAndValues(new long[] {1L}, new float[] {2f}, 3.0).toString();

    assertTrue(text.contains("LongTermsAndValues{"));
    assertTrue(text.contains("uniValue=3.0"));
  }
}
