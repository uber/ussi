/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.MathUtils;
import com.uber.ussi.utils.Utils;

/** Generates signatures using Broder's MinHash algorithm. */
final class MinHashSignatureGenerator extends SignatureGenerator {
  private static final int PRIME_MERSENNE_8 = (1 << 31) - 1;

  MinHashSignatureGenerator() {
    super(/* weighted */ false, /* comparisonValueApproximationSafetyMargin */ 0.1);
  }

  @Override
  protected long[] generateSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    int numTerms = termsAndValues.termsLength();
    long[] hashedTerms = new long[numTerms];
    int[] signs = new int[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      hashedTerms[i] = Utils.longHashCode(termsAndValues.getTerm(i));
      signs[i] = (int) Math.signum(termsAndValues.getValue(i));
    }

    long[] signatures = new long[numSignatures];
    for (int signatureIndex = 0; signatureIndex < numSignatures; ++signatureIndex) {
      long seed = MathUtils.mix64(signatureIndex);
      long a = MathUtils.seedToLong(seed, 0);
      long b = MathUtils.seedToLong(seed, 1);
      int minHash = Integer.MAX_VALUE;
      long selectedTerm = 0L;
      int selectedSign = 0;
      for (int termIndex = 0; termIndex < numTerms; ++termIndex) {
        int hash = Long.hashCode((a * hashedTerms[termIndex] + b) % PRIME_MERSENNE_8);
        if (hash <= minHash) {
          minHash = hash;
          selectedTerm = hashedTerms[termIndex];
          selectedSign = signs[termIndex];
        }
      }
      signatures[signatureIndex] = selectedTerm * selectedSign;
    }
    return signatures;
  }
}
