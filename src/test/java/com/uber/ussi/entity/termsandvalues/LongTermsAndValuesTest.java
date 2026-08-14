package com.uber.ussi.entity.termsandvalues;

import static com.uber.ussi.TestLongObjectMaps.longHashSet;
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
import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.error.ArraysSizeMismatchError;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LongTermsAndValuesTest {

  private static final double DELTA = 1e-9;

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
    assertEquals(5.0, record.getUniValue(), DELTA);
  }

  @Test
  void nullArraysBecomeEmpty() {
    LongTermsAndValues record = new LongTermsAndValues(null, null, 0.0);

    assertEquals(0, record.termsLength());
    assertEquals(0, record.valuesLength());
  }

  @Test
  void newWithFilteredTermsPreservesAlignmentAndRecomputesUniValue() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record =
        new LongTermsAndValues(new long[] {1, 2, 3}, new float[] {1, 2, 3}, 14.0);

    LongTermsAndValues filtered = record.newWithFilteredTerms(longHashSet(2), comparator);

    assertArrayEquals(new long[] {1, 3}, filtered.getTerms());
    assertArrayEquals(new float[] {1, 3}, filtered.getValues());
    assertEquals(10.0, filtered.getUniValue(), DELTA);
  }

  @Test
  void newWithFilteredTermsReturnsSameRecordWhenNothingIsRemoved() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);

    assertSame(record, record.newWithFilteredTerms(new LongHashSet(), comparator));
    assertSame(record, record.newWithFilteredTerms(longHashSet(2), comparator));
  }

  @Test
  void newWithFilteredTermsCanRemoveEveryTermAndSupportsSequences() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);
    LongTermsAndValues sequence = new LongTermsAndValues(new long[] {1, 2}, new float[0], 0.0);

    LongTermsAndValues emptySparse = sparse.newWithFilteredTerms(longHashSet(1), comparator);
    LongTermsAndValues filteredSequence = sequence.newWithFilteredTerms(longHashSet(1), comparator);

    assertEquals(0, emptySparse.termsLength());
    assertEquals(0, emptySparse.valuesLength());
    assertEquals(0.0, emptySparse.getUniValue(), DELTA);
    assertArrayEquals(new long[] {2}, filteredSequence.getTerms());
    assertEquals(0, filteredSequence.valuesLength());
  }

  @Test
  void newWithFilteredTermsRejectsNullArguments() {
    Comparator comparator =
        ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    LongTermsAndValues record = new LongTermsAndValues(new long[] {1}, new float[] {2}, 4.0);

    assertThrows(NullPointerException.class, () -> record.newWithFilteredTerms(null, comparator));
    assertThrows(
        NullPointerException.class, () -> record.newWithFilteredTerms(new LongHashSet(), null));
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
    assertEquals(5.0, record.getUniValue(), DELTA);
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
    assertEquals(18.0, record.getUniValue(), DELTA);
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
    assertEquals(0.0, record.getUniValue(), DELTA);
  }

  @Test
  void verifyComparablePairAcceptsDifferentSparseLengths() {
    LongTermsAndValues first = new LongTermsAndValues(new long[] {1L}, new float[] {1f}, 1.0);
    LongTermsAndValues second =
        new LongTermsAndValues(new long[] {1L, 2L}, new float[] {1f, 1f}, 2.0);

    LongTermsAndValues.verifyComparablePair(first, second);
  }

  @Test
  void verifyComparablePairRejectsMixedShapesAndSequences() {
    LongTermsAndValues dense = new LongTermsAndValues(new long[0], new float[] {1f}, 1.0);
    LongTermsAndValues sparse = new LongTermsAndValues(new long[] {1L}, new float[] {1f}, 1.0);
    LongTermsAndValues sequence = new LongTermsAndValues(new long[] {1L}, new float[0], 0.0);

    assertThrows(
        ArraysSizeMismatchError.class,
        () -> LongTermsAndValues.verifyComparablePair(dense, sparse));
    assertThrows(
        IllegalArgumentException.class,
        () -> LongTermsAndValues.verifyComparablePair(sequence, sequence));
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
