/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

public abstract class Comparator implements Serializable {

  protected final ComparatorNormalizer comparatorNormalizer;
  private final double zeroSimilarityComparatorValue;

  protected Comparator(ComparatorNormalizer comparatorNormalizer) {
    if (comparatorNormalizer == null) {
      throw new NullPointerException("The comparatorNormalizer is null.");
    }
    this.comparatorNormalizer = comparatorNormalizer;
    this.zeroSimilarityComparatorValue =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(0.0);
  }

  /**
   * Returns the comparison value of the two records. Below minSimilarity the value only has to
   * correspond to a similarity below that threshold, so implementations may stop scanning early.
   */
  protected abstract double compareInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError;

  /** Values below minSimilarity are collapsed to the comparator value for zero similarity. */
  final double compare(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError, IllegalArgumentException, NullPointerException {
    if (termsAndValues1.getUniValue() == Constants.UNSET_UNI_VALUE
        || termsAndValues2.getUniValue() == Constants.UNSET_UNI_VALUE) {
      throw new IllegalArgumentException(
          String.format(
              "The comparator was called on TermsAndValues with unset uniValues (%s), (%s).",
              termsAndValues1, termsAndValues2));
    }
    LongTermsAndValues.validateComparablePair(termsAndValues1, termsAndValues2);
    if (!mayPassLengthFiltering(
        /* uniValue1 */ termsAndValues1.getUniValue(),
        /* uniValue2 */ termsAndValues2.getUniValue(),
        /* minSimilarity */ minSimilarity)) {
      return zeroSimilarityComparatorValue;
    }
    double comparatorValue = compareInternal(termsAndValues1, termsAndValues2, minSimilarity);
    double normalizedSimilarity =
        comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(comparatorValue);
    if (normalizedSimilarity < minSimilarity) {
      return zeroSimilarityComparatorValue;
    }
    return comparatorValue;
  }

  public final double getSimilarity(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError, IllegalArgumentException, NullPointerException {
    return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
        compare(termsAndValues1, termsAndValues2, minSimilarity));
  }

  /**
   * Returns the value this comparator reports at {@code normalizedSimilarityValue}, the inverse of
   * what {@link #getSimilarity} returns. A threshold reaches the API as a similarity and every
   * bound is expressed in the comparator's own units, so anything deriving a bound outside this
   * class converts it here rather than holding the normalizer.
   */
  public final double fromSimilarity(double normalizedSimilarityValue) {
    return comparatorNormalizer.normalizedSimilarityValueToComparatorValue(
        normalizedSimilarityValue);
  }

  public abstract double getUniTransformedValue(float value);

  /**
   * Returns the unilateral value of a record's canonical arrays. Comparators whose records carry
   * no values derive it from the terms instead.
   */
  public double computeUniValue(long[] terms, float[] values) {
    return computeUniValue(values);
  }

  /** Returns the unilateral value used to length-filter records in an index. */
  public double computeUniValue(float[] values) {
    MathUtils.StableSumAccumulator accumulator = new MathUtils.StableSumAccumulator();
    for (float value : values) {
      accumulator.add(getUniTransformedValue(value));
    }
    return accumulator.getSum();
  }

  /** Computes the unilateral value without copying the record's values. */
  public double computeUniValue(LongTermsAndValues termsAndValues) {
    MathUtils.StableSumAccumulator accumulator = new MathUtils.StableSumAccumulator();
    for (int i = 0; i < termsAndValues.valuesLength(); ++i) {
      accumulator.add(getUniTransformedValue(termsAndValues.getValue(i)));
    }
    return accumulator.getSum();
  }

  /**
   * Returns the prefix sum, over the uniTransformed values in term order, below which terms can
   * generate candidates; past it no candidate generated can be similar enough. A larger
   * minSimilarity gives a smaller prefix sum, down to a single term at 1.0.
   */
  public final double getMinPrefixSumForTermsAndValues(double uniValue, double minSimilarity) {
    if (uniValue == Constants.UNSET_UNI_VALUE || uniValue < 0.0) {
      throw new IllegalArgumentException(String.format("Invalid uniValue (%s).", uniValue));
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          String.format("minSimilarity must be in [0.0, 1.0], got %s.", minSimilarity));
    }
    return getMinPrefixSumForTermsAndValuesInternal(
            uniValue,
            comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity))
        + MathUtils.EPSILON_12;
  }

  protected abstract double getMinPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue);

  public abstract boolean mayPassLengthFiltering(
      double uniValue1, double uniValue2, double minSimilarity);

  /**
   * Returns whether a query may reach {@code minSimilarity} with a record whose number of terms is
   * in [{@code minNumTerms}, {@code maxNumTerms}]. Comparators without a sound term-count bound
   * return {@code true}.
   */
  public final boolean mayPassNumTermsFiltering(
      LongTermsAndValues query, int minNumTerms, int maxNumTerms, double minSimilarity) {
    Objects.requireNonNull(query, "query");
    if (minNumTerms < 0 || maxNumTerms < minNumTerms) {
      throw new IllegalArgumentException(
          String.format("Invalid number-of-terms range [%s, %s].", minNumTerms, maxNumTerms));
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          String.format("minSimilarity must be in [0.0, 1.0], got %s.", minSimilarity));
    }
    return mayPassNumTermsFilteringInternal(query, minNumTerms, maxNumTerms, minSimilarity);
  }

  protected boolean mayPassNumTermsFilteringInternal(
      LongTermsAndValues query, int minNumTerms, int maxNumTerms, double minSimilarity) {
    return true;
  }

  /**
   * Returns every {@link RecordType} this comparator can read, which decides the searchable
   * structures it can be paired with. An index holds a type that both the comparator and the
   * structure support, so a pairing whose sets are disjoint is a config violation; see
   * {@code IndexType.resolveRecordTypes}.
   */
  public abstract Set<RecordType> getSupportedRecordTypes();

  /*
   * Merge candidate generation accumulates a conjunction: the part of the similarity the query and
   * an indexed row derive from the keys they share. It is the intersection of the two rows for
   * Jaccard and Ruzicka, and the squared distance over the shared keys for L2.
   */

  /**
   * Returns whether this comparator can generate candidates by merging inverted lists, which is
   * exactly whether it implements the conjunction methods below. A comparator that cannot must say
   * so here, so that the config is rejected up front rather than failing partway through a search.
   */
  public boolean supportsMergeCandidateGeneration() {
    return false;
  }

  /** Returns what one key the query and an indexed row share adds to the conjunction. */
  public double conjunctionContribution(float value1, float value2) {
    throw new UnsupportedOperationException(
        "conjunctionContribution is not supported by " + getClass().getSimpleName() + ".");
  }

  /** Returns the normalized similarity implied by a complete conjunction. */
  public double similarityFromConjunction(
      double conjunction,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    throw new UnsupportedOperationException(
        "similarityFromConjunction is not supported by " + getClass().getSimpleName() + ".");
  }

  /**
   * Returns the highest normalized similarity still reachable from a partial conjunction, where
   * {@code unscannedKeysUniValue} bounds what the query's not-yet-merged keys can add.
   */
  public double maxSimilarityFromPartialConjunction(
      double conjunction,
      double unscannedKeysUniValue,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    throw new UnsupportedOperationException(
        "maxSimilarityFromPartialConjunction is not supported by "
            + getClass().getSimpleName()
            + ".");
  }

  /**
   * Returns whether a suffix of per-key bounds is a valid bound on what the query's unscanned keys
   * can still contribute to the conjunction.
   */
  public boolean doesSuffixBoundConjunction() {
    return false;
  }
}
