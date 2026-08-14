/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

/** Shared conversion support for consistent weighted sampling algorithms. */
abstract class BaseCwsSignatureGenerator extends SignatureGenerator {

  BaseCwsSignatureGenerator(double comparisonValueApproximationSafetyMargin) {
    super(/* weighted */ true, comparisonValueApproximationSafetyMargin);
  }

  protected static long[] toLongSignatures(CwsSignature[] signatures) {
    long[] values = new long[signatures.length];
    for (int i = 0; i < signatures.length; ++i) {
      values[i] = signatures[i].longHashCode();
    }
    return values;
  }
}
