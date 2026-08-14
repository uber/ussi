/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.MathUtils;
import com.uber.ussi.utils.Utils;

/** Generates signatures using simplified 0-bit consistent weighted sampling (SCWS). */
final class ScwsSignatureGenerator extends BaseCwsSignatureGenerator {
  private static final int PRIME_1 = 1073741827;
  private static final int PRIME_2 = 1073741831;

  ScwsSignatureGenerator() {
    super(/* comparisonValueApproximationSafetyMargin */ 0.1);
  }

  @Override
  protected long[] generateSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    int numTerms = termsAndValues.termsLength();
    long[] hashedTerms = new long[numTerms];
    double[] weights = new double[numTerms];
    int[] signs = new int[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      hashedTerms[i] = Utils.longHashCode(termsAndValues.getTerm(i));
      float value = termsAndValues.getValue(i);
      weights[i] = Math.abs(value);
      signs[i] = (int) Math.signum(value);
    }

    long[] signatures = new long[numSignatures];
    for (int signatureIndex = 0; signatureIndex < numSignatures; ++signatureIndex) {
      int b = signatureIndex * PRIME_2;
      double minA = Double.MAX_VALUE;
      long selectedTerm = 0L;
      int selectedSign = 0;
      for (int termIndex = 0; termIndex < numTerms; ++termIndex) {
        long gamma = (hashedTerms[termIndex] * PRIME_1 + b) % Integer.MAX_VALUE;
        long seed = MathUtils.mix64(gamma ^ signatureIndex);
        double r = MathUtils.seedToGamma21(seed, 0);
        double c = MathUtils.seedToGamma21(seed, 2);
        double a = c * Math.exp(-r) / weights[termIndex];
        if (a <= minA) {
          minA = a;
          selectedTerm = hashedTerms[termIndex];
          selectedSign = signs[termIndex];
        }
      }
      signatures[signatureIndex] = selectedTerm * selectedSign;
    }
    return signatures;
  }
}
