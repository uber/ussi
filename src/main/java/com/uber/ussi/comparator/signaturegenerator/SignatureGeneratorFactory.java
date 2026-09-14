/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.signaturegenerator;

import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.utils.Constants;
import java.util.Objects;

/** Creates the signature generators supported by the signature-keyed index types. */
public final class SignatureGeneratorFactory {
  public enum SignatureGeneratorType implements ConfigVocabulary {
    I2CWS,
    ICWS,
    MINHASH,
    PCWS,
    SCWS
  }

  private SignatureGeneratorFactory() {}

  public static SignatureGenerator createSignatureGenerator(String signatureGeneratorType) {
    Objects.requireNonNull(signatureGeneratorType, "signatureGeneratorType");
    SignatureGeneratorType type =
        ConfigVocabulary.fromParamValue(SignatureGeneratorType.class, signatureGeneratorType);
    if (type == null) {
      throw new IllegalArgumentException(
          ConfigVocabulary.unsupported(
              Constants.SIGNATURE_GENERATOR, signatureGeneratorType,
              SignatureGeneratorType.class));
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
