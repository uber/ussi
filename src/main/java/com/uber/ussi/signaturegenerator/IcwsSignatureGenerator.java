/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.MathUtils;
import com.uber.ussi.utils.Utils;

/** Generates signatures using improved consistent weighted sampling (ICWS). */
final class IcwsSignatureGenerator extends BaseCwsSignatureGenerator {

  IcwsSignatureGenerator() {
    super(/* comparisonValueApproximationSafetyMargin */ 0.1);
  }

  @Override
  protected long[] generateSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    int numTerms = termsAndValues.termsLength();
    long[] hashedTerms = new long[numTerms];
    double[] logWeights = new double[numTerms];
    int[] signs = new int[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      hashedTerms[i] = Utils.longHashCode(termsAndValues.getTerm(i));
      float value = termsAndValues.getValue(i);
      logWeights[i] = Math.log(Math.abs(value));
      signs[i] = (int) Math.signum(value);
    }

    CwsSignature[] signatures = new CwsSignature[numSignatures];
    for (int signatureIndex = 0; signatureIndex < numSignatures; ++signatureIndex) {
      double minA = Double.MAX_VALUE;
      double selectedY = 0.0;
      long selectedTerm = 0L;
      int selectedSign = 0;
      for (int termIndex = 0; termIndex < numTerms; ++termIndex) {
        long seed = MathUtils.mix64(hashedTerms[termIndex] ^ signatureIndex);
        double r = MathUtils.seedToGamma21(seed, 0);
        double beta = MathUtils.seedToUniform01(seed, 2);
        double c = MathUtils.seedToGamma21(seed, 3);
        double y = Math.exp(r * (Math.floor(logWeights[termIndex] / r + beta) - beta));
        double a = c / (y * Math.exp(r));
        if (a <= minA) {
          minA = a;
          selectedY = y;
          selectedTerm = hashedTerms[termIndex];
          selectedSign = signs[termIndex];
        }
      }
      signatures[signatureIndex] = new CwsSignature(selectedTerm * selectedSign, selectedY);
    }
    return toLongSignatures(signatures);
  }
}
