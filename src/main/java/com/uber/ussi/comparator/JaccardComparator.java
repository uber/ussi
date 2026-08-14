/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.signaturegenerator.SignatureGenerator;
import com.uber.ussi.utils.MathUtils;
import javax.annotation.Nullable;

/** Jaccard similarity over the signed presence of TermsAndValues entries. */
public final class JaccardComparator extends BaseRuzickaComparator {

  JaccardComparator(
      ComparatorNormalizer comparatorNormalizer, @Nullable SignatureGenerator signatureGenerator) {
    super(comparatorNormalizer, signatureGenerator);
  }

  @Override
  public double getUniTransformedValue(float value) {
    return Math.abs(Math.signum(value));
  }

  @Override
  protected boolean mayPassNumTermsFilteringInternal(
      LongTermsAndValues query, int minNumTerms, int maxNumTerms, double minSimilarity) {
    double queryCardinality = query.getUniValue();
    if (queryCardinality <= 0.0 || queryCardinality != query.termsLength()) {
      return true;
    }
    double minJaccard =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    if (minJaccard <= 0.0) {
      return true;
    }
    if (minJaccard > 1.0) {
      return false;
    }
    double minMatchingCardinality = queryCardinality * minJaccard;
    double maxMatchingCardinality = queryCardinality / minJaccard;
    return maxNumTerms + MathUtils.EPSILON >= minMatchingCardinality
        && minNumTerms <= maxMatchingCardinality + MathUtils.EPSILON;
  }
}
