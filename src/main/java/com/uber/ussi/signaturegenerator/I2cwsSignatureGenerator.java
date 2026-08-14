/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.MathUtils;
import com.uber.ussi.utils.Utils;

/** Generates signatures using improved consistent weighted sampling revisited (I2CWS). */
final class I2cwsSignatureGenerator extends BaseCwsSignatureGenerator {

  I2cwsSignatureGenerator() {
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
      double selectedBeta = 0.0;
      double selectedR = 0.0;
      int selectedTermIndex = 0;
      long selectedTerm = 0L;
      int selectedSign = 0;
      for (int termIndex = 0; termIndex < numTerms; ++termIndex) {
        long seed = MathUtils.mix64(hashedTerms[termIndex] ^ signatureIndex);
        double r1 = MathUtils.seedToGamma21(seed, 0);
        double r2 = MathUtils.seedToGamma21(seed, 2);
        double beta1 = MathUtils.seedToUniform01(seed, 4);
        double beta2 = MathUtils.seedToUniform01(seed, 5);
        double c = MathUtils.seedToGamma21(seed, 6);
        double t2 = Math.floor(logWeights[termIndex] / r2 + beta2);
        double z = Math.exp(r2 * (t2 - beta2 + 1.0));
        double a = c / z;
        if (a <= minA) {
          minA = a;
          selectedBeta = beta1;
          selectedR = r1;
          selectedTermIndex = termIndex;
          selectedTerm = hashedTerms[termIndex];
          selectedSign = signs[termIndex];
        }
      }
      double t1 = Math.floor(logWeights[selectedTermIndex] / selectedR + selectedBeta);
      double selectedY = Math.exp(selectedR * (t1 - selectedBeta));
      signatures[signatureIndex] = new CwsSignature(selectedTerm * selectedSign, selectedY);
    }
    return toLongSignatures(signatures);
  }
}
