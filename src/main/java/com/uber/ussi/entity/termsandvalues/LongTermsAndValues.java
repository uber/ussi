/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

import com.carrotsearch.hppc.LongHashSet;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.error.ArraysSizeMismatchError;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Internal USSI record representation used after ingestion.
 *
 * <p>The public record uses String terms. Internally, terms are encoded as primitive longs to
 * reduce memory overhead and speed up comparisons.
 */
public final class LongTermsAndValues {
  private static final long[] EMPTY_TERMS = new long[0];
  private static final float[] EMPTY_VALUES = new float[0];

  private final long[] terms;
  private final float[] values;
  // Comparator-specific summary value used for pruning; for L2 this is the squared vector norm.
  private final double uniValue;

  /** Trusted construction path for canonical terms and a comparator-derived uniValue. */
  LongTermsAndValues(long[] terms, float[] values, double uniValue) {
    this.terms = terms == null || terms.length == 0 ? EMPTY_TERMS : terms.clone();
    this.values = values == null || values.length == 0 ? EMPTY_VALUES : values.clone();
    this.uniValue = uniValue;
    validateTermsAndValuesLength(this.terms, this.values);
  }

  /**
   * Encodes and canonicalizes a public record, then materializes its comparator-specific uniValue.
   */
  public static LongTermsAndValues from(
      TermsAndValues termsAndValues, TermEncoder termEncoder, Comparator comparator) {
    Objects.requireNonNull(termsAndValues, "termsAndValues is null.");
    Objects.requireNonNull(termEncoder, "termEncoder is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    long[] encodedTerms = encodeTerms(termsAndValues, termEncoder);
    float[] values = termsAndValues.getValues();
    CanonicalTermsAndValues canonical = canonicalize(encodedTerms, values);
    return new LongTermsAndValues(
        canonical.terms, canonical.values, comparator.computeUniValue(canonical.values));
  }

  public long[] getTerms() {
    return terms.clone();
  }

  public float[] getValues() {
    return values.clone();
  }

  public long getTerm(int i) {
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

  public double getUniValue() {
    return uniValue;
  }

  /**
   * Returns whether this record and the other record share at least one term. Terms are sorted and
   * distinct within a record, so the intersection check is a single linear merge. Records without
   * terms (dense records) never share a term.
   */
  public boolean sharesAnyTerm(LongTermsAndValues other) {
    Objects.requireNonNull(other, "other is null.");
    int thisIndex = 0;
    int otherIndex = 0;
    while (thisIndex < terms.length && otherIndex < other.terms.length) {
      if (terms[thisIndex] == other.terms[otherIndex]) {
        return true;
      }
      if (terms[thisIndex] < other.terms[otherIndex]) {
        ++thisIndex;
      } else {
        ++otherIndex;
      }
    }
    return false;
  }

  /** Returns this record without filtered terms, recomputing the comparator-specific uniValue. */
  public LongTermsAndValues newWithFilteredTerms(
      LongHashSet filteredOutTerms, Comparator comparator) {
    Objects.requireNonNull(filteredOutTerms, "filteredOutTerms is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    if (filteredOutTerms.isEmpty() || terms.length == 0) {
      return this;
    }

    int numIncludedTerms = 0;
    for (long term : terms) {
      if (!filteredOutTerms.contains(term)) {
        ++numIncludedTerms;
      }
    }
    if (numIncludedTerms == terms.length) {
      return this;
    }

    long[] includedTerms = new long[numIncludedTerms];
    float[] includedValues = values.length == 0 ? EMPTY_VALUES : new float[numIncludedTerms];
    int includedIndex = 0;
    for (int i = 0; i < terms.length; ++i) {
      if (filteredOutTerms.contains(terms[i])) {
        continue;
      }
      includedTerms[includedIndex] = terms[i];
      if (values.length > 0) {
        includedValues[includedIndex] = values[i];
      }
      ++includedIndex;
    }
    return new LongTermsAndValues(
        includedTerms, includedValues, comparator.computeUniValue(includedValues));
  }

  /** Validates that two records can be aligned for a numeric comparator. */
  public static void verifyComparablePair(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2) {
    Objects.requireNonNull(termsAndValues1, "termsAndValues1 is null.");
    Objects.requireNonNull(termsAndValues2, "termsAndValues2 is null.");
    /*
     * TODO: Remove this check when sequence comparators are implemented. Sequence
     * TermsAndValues have non-empty terms and no values.
     */
    if (termsAndValues1.valuesLength() == 0 || termsAndValues2.valuesLength() == 0) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot compare TermsAndValues with no values "
                  + "(termsAndValues1 = %s, termsAndValues2 = %s).",
              termsAndValues1, termsAndValues2));
    }
    if (termsAndValues1.termsLength() == 0 || termsAndValues2.termsLength() == 0) {
      if (termsAndValues1.termsLength() != termsAndValues2.termsLength()) {
        throw new ArraysSizeMismatchError(
            String.format(
                "If either terms array is empty, both must be empty "
                    + "(termsAndValues1 = %s, termsAndValues2 = %s).",
                termsAndValues1, termsAndValues2));
      }
      if (termsAndValues1.valuesLength() != termsAndValues2.valuesLength()) {
        throw new ArraysSizeMismatchError(
            String.format(
                "Dense TermsAndValues must have equal value lengths, got %s and %s.",
                termsAndValues1.valuesLength(), termsAndValues2.valuesLength()));
      }
    }
  }

  private static void validateTermsAndValuesLength(long[] terms, float[] values) {
    if (terms.length > 0 && values.length > 0 && terms.length != values.length) {
      throw new IllegalArgumentException(
          String.format(
              "The terms array length (%s) and values array length (%s) should match when both are populated.",
              terms.length, values.length));
    }
  }

  private static long[] encodeTerms(TermsAndValues termsAndValues, TermEncoder termEncoder) {
    long[] encodedTerms = new long[termsAndValues.termsLength()];
    for (int i = 0; i < encodedTerms.length; ++i) {
      encodedTerms[i] = termEncoder.encode(termsAndValues.getTerm(i));
    }
    return encodedTerms;
  }

  // Sort terms, sum values for each term, and drop zero sums unless all sums are zero.
  private static CanonicalTermsAndValues canonicalize(long[] terms, float[] values) {
    if (terms.length == 0 || values.length == 0) {
      return new CanonicalTermsAndValues(terms, values);
    }
    TreeMap<Long, Double> valueByTerm = new TreeMap<>();
    for (int i = 0; i < terms.length; ++i) {
      valueByTerm.merge(terms[i], (double) values[i], Double::sum);
    }

    boolean allValuesAreZero = true;
    int numCanonicalValues = 0;
    for (double value : valueByTerm.values()) {
      if (value != 0.0) {
        allValuesAreZero = false;
        ++numCanonicalValues;
      }
    }
    if (allValuesAreZero) {
      numCanonicalValues = valueByTerm.size();
    }

    long[] canonicalTerms = new long[numCanonicalValues];
    float[] canonicalValues = new float[numCanonicalValues];
    int index = 0;
    // TreeMap iteration is ordered by encoded term, producing merge-scan-ready parallel arrays.
    for (Map.Entry<Long, Double> entry : valueByTerm.entrySet()) {
      if (!allValuesAreZero && entry.getValue() == 0.0) {
        continue;
      }
      canonicalTerms[index] = entry.getKey();
      canonicalValues[index] = entry.getValue().floatValue();
      ++index;
    }
    return new CanonicalTermsAndValues(canonicalTerms, canonicalValues);
  }

  @Override
  public String toString() {
    return "LongTermsAndValues{"
        + "terms="
        + Arrays.toString(terms)
        + ", values="
        + Arrays.toString(values)
        + ", uniValue="
        + uniValue
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LongTermsAndValues)) {
      return false;
    }
    LongTermsAndValues that = (LongTermsAndValues) o;
    return Double.compare(that.uniValue, uniValue) == 0
        && Arrays.equals(terms, that.terms)
        && Arrays.equals(values, that.values);
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(terms);
    result = 31 * result + Arrays.hashCode(values);
    long uniValueBits = Double.doubleToLongBits(uniValue);
    result = 31 * result + (int) (uniValueBits ^ (uniValueBits >>> 32));
    return result;
  }

  private static final class CanonicalTermsAndValues {
    private final long[] terms;
    private final float[] values;

    private CanonicalTermsAndValues(long[] terms, float[] values) {
      this.terms = terms;
      this.values = values;
    }
  }

  /** Converts public string terms into internal primitive long terms. */
  public interface TermEncoder {
    long encode(String term);
  }
}
