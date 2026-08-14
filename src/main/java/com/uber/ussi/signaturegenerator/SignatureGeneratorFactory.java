/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import java.util.Locale;
import java.util.Objects;

/** Creates the signature generators supported by the sparse approximate index. */
public final class SignatureGeneratorFactory {
  public enum SignatureGeneratorType {
    I2CWS,
    ICWS,
    MINHASH,
    PCWS,
    SCWS
  }

  private SignatureGeneratorFactory() {}

  public static SignatureGenerator createSignatureGenerator(String signatureGeneratorType) {
    Objects.requireNonNull(signatureGeneratorType, "signatureGeneratorType");
    SignatureGeneratorType type;
    try {
      type = SignatureGeneratorType.valueOf(signatureGeneratorType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("Unsupported signature generator type (%s).", signatureGeneratorType), e);
    }
    return createSignatureGenerator(type);
  }

  public static SignatureGenerator createSignatureGenerator(SignatureGeneratorType type) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case I2CWS -> new I2cwsSignatureGenerator();
      case ICWS -> new IcwsSignatureGenerator();
      case MINHASH -> new MinHashSignatureGenerator();
      case PCWS -> new PcwsSignatureGenerator();
      case SCWS -> new ScwsSignatureGenerator();
    };
  }
}
