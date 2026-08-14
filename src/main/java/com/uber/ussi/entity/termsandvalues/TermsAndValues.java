/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

import java.util.Arrays;

/**
 * Public USSI record representation.
 *
 * <p>A TermsAndValues can represent:
 *
 * <ul>
 *   <li>dense features: empty terms, non-empty values
 *   <li>sparse features: terms and values have the same non-zero length
 *   <li>sequences/text: non-empty terms, empty values
 * </ul>
 */
public final class TermsAndValues {
  private static final String[] EMPTY_TERMS = new String[0];
  private static final float[] EMPTY_VALUES = new float[0];

  private final String[] terms;
  private final float[] values;

  public TermsAndValues(String[] terms, float[] values) {
    this.terms = terms == null || terms.length == 0 ? EMPTY_TERMS : terms.clone();
    this.values = values == null || values.length == 0 ? EMPTY_VALUES : values.clone();
    validateTermsAndValuesLength(this.terms, this.values);
  }

  public TermsAndValues() {
    this(EMPTY_TERMS, EMPTY_VALUES);
  }

  public String[] getTerms() {
    return terms.clone();
  }

  public float[] getValues() {
    return values.clone();
  }

  public String getTerm(int i) {
    return terms[i];
  }

  public float getValue(int i) {
    return values[i];
  }

  public int termsLength() {
    return terms.length;
  }

  public int valuesLength() {
    return values.length;
  }

  public boolean isDenseFeature() {
    return terms.length == 0 && values.length > 0;
  }

  public boolean isSparseFeature() {
    return terms.length > 0 && values.length > 0;
  }

  public boolean isSequence() {
    return terms.length > 0 && values.length == 0;
  }

  private static void validateTermsAndValuesLength(String[] terms, float[] values) {
    if (terms.length > 0 && values.length > 0 && terms.length != values.length) {
      throw new IllegalArgumentException(
          String.format(
              "The terms array length (%s) and values array length (%s) should match when both are populated.",
              terms.length, values.length));
    }
  }

  @Override
  public String toString() {
    return "TermsAndValues{"
        + "terms="
        + Arrays.toString(terms)
        + ", values="
        + Arrays.toString(values)
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TermsAndValues)) {
      return false;
    }
    TermsAndValues that = (TermsAndValues) o;
    return Arrays.equals(terms, that.terms) && Arrays.equals(values, that.values);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(terms) + Arrays.hashCode(values);
  }
}
