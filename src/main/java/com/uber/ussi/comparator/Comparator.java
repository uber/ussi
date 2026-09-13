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
   * Returns the comparison value of the two records. If their similarity is below minSimilarity,
   * the returned value only has to correspond to a similarity below that threshold and need not be
   * the exact comparison value. This allows implementations to stop before scanning all values.
   */
  protected abstract double compareInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError;

  /**
   * Returns the comparison value of the two records. Values below minSimilarity are normalized to
   * the comparator value representing zero similarity.
   */
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

  public abstract double getUniTransformedValue(float value);

  /**
   * Returns the unilateral value of a record's canonical arrays. Comparators whose records carry
   * no values, such as the sequence comparators, derive it from the terms instead.
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
   * Assuming all the terms are sorted by "some" order, and the uniTransformation is applied to all
   * values, and the partial sums are computed on the uniTransformed-values based on the term order,
   * returns the partial sum of the term, below which all terms can generate candidates, and after
   * any candidate generated cannot be similar to the search TermsAndValues. The larger the
   * minSimilarity the smaller the minPrefixSum. For instance, if the similarity is 1.0, then only
   * one term can generate candidates, since all the terms in the TermsAndValues (and their values)
   * have to match. In that case, the term that generates the least candidates should be the one
   * used to generate candidates.
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
   * in the inclusive range [{@code minNumTerms}, {@code maxNumTerms}]. Comparators without a sound
   * term-count bound conservatively return {@code true}.
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
   * Returns every {@link RecordType} this comparator can read, which is what decides the
   * searchable structures it can be paired with. There is no default: a comparator is defined as
   * much by the layout it reads as by the similarity it computes, so each one states its own set
   * rather than inheriting one record type as the norm and overriding for the rest.
   *
   * <p>A comparator may read more than one record type. A structure that stores a single record
   * type is compatible with a comparator when that type is in this set.
   */
  public abstract Set<RecordType> getSupportedRecordTypes();

  /*
   * Merge candidate generation accumulates a conjunction: the part of the similarity that the query
   * and an indexed row derive from the sparse keys they share. What that means is up to each
   * comparator. For Jaccard and Ruzicka the conjunction is the intersection of the two rows, and
   * for L2 it is the squared distance over the shared keys. Comparators that leave these
   * unimplemented cannot be paired with the merge generator.
   */

  /**
   * Returns whether this comparator can generate candidates by merging inverted lists, which is
   * exactly whether it implements the conjunction methods below.
   *
   * <p>Comparators that cannot must say so here rather than relying on those methods throwing, so
   * that a config pairing one with the merge generator is rejected up front instead of failing
   * partway through a search.
   */
  public boolean supportsMergeCandidateGeneration() {
    return false;
  }

  /** Returns what one sparse key the query and an indexed row share adds to the conjunction. */
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
   * {@code unscannedKeysUniValue} bounds what the query's not-yet-merged sparse keys can add.
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
