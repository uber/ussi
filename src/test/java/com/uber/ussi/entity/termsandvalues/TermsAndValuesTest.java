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
}
