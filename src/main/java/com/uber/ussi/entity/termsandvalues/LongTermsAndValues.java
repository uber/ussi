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
 * Internal USSI record representation used after ingestion. Terms are encoded as primitive longs
 * rather than the public record's Strings, to cut memory and speed up comparisons.
 */
public final class LongTermsAndValues {
  private static final long[] EMPTY_TERMS = new long[0];
  private static final float[] EMPTY_VALUES = new float[0];

  private final long[] terms;
  private final float[] values;
  /**
   * Comparator-specific summary value used for pruning. For L2 this is the squared vector norm.
   *
   * <p>It also records whether the row is deleted: a value that is not a number marks a row an
   * index no longer returns. Every record reaching an index is validated to carry a finite,
   * non-negative value, so no live row can carry that mark.
   */
  private final double uniValue;

  /** Trusted construction path for canonical terms and a comparator-derived uniValue. */
  LongTermsAndValues(long[] terms, float[] values, double uniValue) {
    this.terms = terms == null || terms.length == 0 ? EMPTY_TERMS : terms.clone();
    this.values = values == null || values.length == 0 ? EMPTY_VALUES : values.clone();
    this.uniValue = uniValue;
    validateTermsAndValuesLength(this.terms, this.values);
  }

  public static LongTermsAndValues from(
      TermsAndValues termsAndValues, TermEncoder termEncoder, Comparator comparator) {
    Objects.requireNonNull(termsAndValues, "termsAndValues is null.");
    Objects.requireNonNull(termEncoder, "termEncoder is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    long[] encodedTerms = encodeTerms(termsAndValues, termEncoder);
    float[] values = termsAndValues.getValues();
    CanonicalTermsAndValues canonical = canonicalize(encodedTerms, values);
    return new LongTermsAndValues(
        canonical.terms,
        canonical.values,
        comparator.computeUniValue(canonical.terms, canonical.values));
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
   * Returns this record as a deleted row, which an index keeps in place of the original until it is
   * rebuilt. Its unilateral value is no longer a number, which is how the row is recognised as
   * deleted and, where a similarity is derived from that value, how the row is excluded.
   */
  public LongTermsAndValues markAsDeleted() {
    return new LongTermsAndValues(terms, values, Double.NaN);
  }

  /**
   * Terms are sorted and distinct within a record, so this is a single linear merge. Dense records
   * carry no terms and so never share one.
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

  /** Returns this record without the given terms, recomputing the comparator-specific uniValue. */
  public LongTermsAndValues newWithoutTerms(LongHashSet discardedTerms, Comparator comparator) {
    Objects.requireNonNull(discardedTerms, "discardedTerms is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    if (discardedTerms.isEmpty() || terms.length == 0) {
      return this;
    }

    int numIncludedTerms = 0;
    for (long term : terms) {
      if (!discardedTerms.contains(term)) {
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
      if (discardedTerms.contains(terms[i])) {
        continue;
      }
      includedTerms[includedIndex] = terms[i];
      if (values.length > 0) {
        includedValues[includedIndex] = values[i];
      }
      ++includedIndex;
    }
    return new LongTermsAndValues(
        includedTerms, includedValues, comparator.computeUniValue(includedTerms, includedValues));
  }

  /**
   * Returns this sequence's term multiset as a sparse record: distinct terms ascending, values are
   * occurrence counts. Candidate generation for an order-sensitive distance runs over this form,
   * because two sequences within a given edit distance have multisets within a bounded L1 distance.
   * The counts sum to the sequence length, so the Uni value is unchanged.
   */
  public LongTermsAndValues toTermMultiset(Comparator comparator) {
    Objects.requireNonNull(comparator, "comparator is null.");
    if (values.length != 0) {
      throw new IllegalArgumentException(
          String.format(
              "Only a record without values is a sequence, got terms=%s values=%s.",
              Arrays.toString(terms), Arrays.toString(values)));
    }
    // Sorting a copy and counting equal runs avoids boxing every term in a sorted map.
    long[] sortedTerms = terms.clone();
    Arrays.sort(sortedTerms);
    int numDistinctTerms = 0;
    for (int i = 0; i < sortedTerms.length; ++i) {
      if (i == 0 || sortedTerms[i] != sortedTerms[i - 1]) {
        ++numDistinctTerms;
      }
    }
    long[] distinctTerms = new long[numDistinctTerms];
    float[] counts = new float[numDistinctTerms];
    int index = -1;
    for (int i = 0; i < sortedTerms.length; ++i) {
      if (i == 0 || sortedTerms[i] != sortedTerms[i - 1]) {
        distinctTerms[++index] = sortedTerms[i];
      }
      ++counts[index];
    }
    return new LongTermsAndValues(
        distinctTerms, counts, comparator.computeUniValue(distinctTerms, counts));
  }

  /** Validates that two records can be aligned by the comparator that is about to score them. */
  public static void validateComparablePair(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2) {
    Objects.requireNonNull(termsAndValues1, "termsAndValues1 is null.");
    Objects.requireNonNull(termsAndValues2, "termsAndValues2 is null.");
    // A sequence carries no values, so two sequences need no length
    // agreement, but a sequence cannot be aligned against a record that carries values.
    boolean isSequence1 = termsAndValues1.valuesLength() == 0;
    boolean isSequence2 = termsAndValues2.valuesLength() == 0;
    if (isSequence1 != isSequence2) {
      throw new ArraysSizeMismatchError(
          String.format(
              "Cannot compare TermsAndValues carrying values against TermsAndValues carrying "
                  + "none (termsAndValues1 = %s, termsAndValues2 = %s).",
              termsAndValues1, termsAndValues2));
    }
    if (isSequence1) {
      // A record with neither terms nor values carries nothing to compare under any comparator.
      if (termsAndValues1.termsLength() == 0 && termsAndValues2.termsLength() == 0) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot compare TermsAndValues with no terms and no values "
                    + "(termsAndValues1 = %s, termsAndValues2 = %s).",
                termsAndValues1, termsAndValues2));
      }
      return;
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

  public interface TermEncoder {
    long encode(String term);
  }
}
