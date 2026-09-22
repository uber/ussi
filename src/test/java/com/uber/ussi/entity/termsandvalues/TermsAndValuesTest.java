package com.uber.ussi.entity.termsandvalues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TermsAndValuesTest {

  @Test
  void defaultConstructorCreatesEmptyRecord() {
    TermsAndValues record = new TermsAndValues();

    assertEquals(0, record.termsLength());
    assertEquals(0, record.valuesLength());
  }

  @Test
  void accessorsReturnStoredData() {
    TermsAndValues record = new TermsAndValues(new String[] {"a", "b"}, new float[] {1f, 2f});

    assertArrayEquals(new String[] {"a", "b"}, record.getTerms());
    assertArrayEquals(new float[] {1f, 2f}, record.getValues());
    assertEquals("a", record.getTerm(0));
    assertEquals(2f, record.getValue(1));
    assertEquals(2, record.termsLength());
    assertEquals(2, record.valuesLength());
  }

  @Test
  void denseFeatureHasValuesButNoTerms() {
    TermsAndValues record = new TermsAndValues(new String[0], new float[] {1f});

    assertTrue(record.isDenseFeature());
    assertFalse(record.isSparseFeature());
    assertFalse(record.isSequence());
  }

  @Test
  void sparseFeatureHasTermsAndValues() {
    TermsAndValues record = new TermsAndValues(new String[] {"a"}, new float[] {1f});

    assertTrue(record.isSparseFeature());
  }

  @Test
  void sequenceHasTermsButNoValues() {
    TermsAndValues record = new TermsAndValues(new String[] {"a"}, new float[0]);

    assertTrue(record.isSequence());
  }

  @Test
  void mismatchedTermAndValueLengthsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TermsAndValues(new String[] {"a", "b"}, new float[] {1f}));
  }

  @Test
  void equalsAndHashCodeUseContents() {
    TermsAndValues first = new TermsAndValues(new String[] {"a"}, new float[] {1f});
    TermsAndValues second = new TermsAndValues(new String[] {"a"}, new float[] {1f});
    TermsAndValues different = new TermsAndValues(new String[] {"b"}, new float[] {1f});

    assertEquals(first, second);
    assertEquals(first, first);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, different);
    assertNotEquals(first, "not-a-record");
  }

  @Test
  void toStringIncludesTermsAndValues() {
    String text = new TermsAndValues(new String[] {"a"}, new float[] {1f}).toString();

    assertTrue(text.contains("TermsAndValues{"));
  }

  /**
   * A record's type is how it is addressed, and each of the three is told apart by which of its two
   * arrays is populated. A record with neither populated is none of them.
   */
  @Test
  void tellsTheThreeRecordTypesApartByWhichArraysArePopulated() {
    Object[][] termsValuesDenseSparseAndSequence = {
      {new String[0], new float[] {1f}, true, false, false},
      {new String[] {"a"}, new float[] {1f}, false, true, false},
      {new String[] {"a"}, new float[0], false, false, true},
      {new String[0], new float[0], false, false, false},
    };
    for (Object[] testCase : termsValuesDenseSparseAndSequence) {
      TermsAndValues record = new TermsAndValues((String[]) testCase[0], (float[]) testCase[1]);

      assertEquals(testCase[2], record.isDenseFeature(), record + " is dense");
      assertEquals(testCase[3], record.isSparseFeature(), record + " is sparse");
      assertEquals(testCase[4], record.isSequence(), record + " is a sequence");
    }
  }

  /** A null array and an empty one describe the same absent half of a record. */
  @Test
  void treatsANullArrayAsAnEmptyOne() {
    TermsAndValues fromNulls = new TermsAndValues(null, null);
    TermsAndValues fromEmpties = new TermsAndValues(new String[0], new float[0]);

    assertEquals(fromEmpties, fromNulls);
    assertEquals(0, fromNulls.getTerms().length);
    assertEquals(0, fromNulls.getValues().length);

    assertEquals(
        new TermsAndValues(new String[0], new float[] {1f}),
        new TermsAndValues(null, new float[] {1f}));
    assertEquals(
        new TermsAndValues(new String[] {"a"}, new float[0]),
        new TermsAndValues(new String[] {"a"}, null));
  }

  /** Two records differing in only one of their two arrays are not the same record. */
  @Test
  void distinguishesRecordsDifferingInEitherArrayAlone() {
    TermsAndValues base = new TermsAndValues(new String[] {"a"}, new float[] {1f});

    assertNotEquals(base, new TermsAndValues(new String[] {"b"}, new float[] {1f}));
    assertNotEquals(base, new TermsAndValues(new String[] {"a"}, new float[] {2f}));
    assertEquals(base, new TermsAndValues(new String[] {"a"}, new float[] {1f}));
  }
}
