/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.signaturegenerator.SignatureGenerator;
import com.uber.ussi.signaturegenerator.SignatureGeneratorFactory;
import com.uber.ussi.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.utils.Constants;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

public class ComparatorFactory {

  private ComparatorFactory() {}

  public enum COMPARATOR_TYPE {
    L2,
    JACCARD,
    RUZICKA
  }

  public static Comparator createComparator(
      String comparatorType,
      Map<String, String> comparatorParams,
      ComparatorNormalizer comparatorNormalizer)
      throws ComparatorCreationError {
    String comparatorTypeLowerCase = comparatorType.toLowerCase(Locale.ROOT);
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.L2.name().toLowerCase(Locale.ROOT))) {
      if (comparatorParams.containsKey(Constants.SIGNATURE_GENERATOR_TYPE)) {
        throw new ComparatorCreationError("L2 does not support signature generation.");
      }
      return new L2Comparator(comparatorNormalizer);
    }
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.JACCARD.name().toLowerCase(Locale.ROOT))) {
      return new JaccardComparator(
          comparatorNormalizer,
          createSignatureGenerator(comparatorParams, SignatureGeneratorType.MINHASH));
    }
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.RUZICKA.name().toLowerCase(Locale.ROOT))) {
      return new RuzickaComparator(
          comparatorNormalizer,
          createSignatureGenerator(
              comparatorParams,
              SignatureGeneratorType.I2CWS,
              SignatureGeneratorType.ICWS,
              SignatureGeneratorType.PCWS,
              SignatureGeneratorType.SCWS));
    }
    throw new ComparatorCreationError(
        String.format("Unsupported Comparator type (%s).", comparatorTypeLowerCase));
  }

  public static Comparator createComparator(NamespaceConfig namespaceConfig)
      throws ComparatorCreationError, NumberFormatException {
    return createComparator(
        namespaceConfig.getComparatorType(),
        namespaceConfig.getComparatorParams(),
        ComparatorNormalizerFactory.createComparatorNormalizer(
            namespaceConfig.getComparatorNormalizerType(),
            namespaceConfig.getComparatorNormalizerParams()));
  }

  @Nullable
  private static SignatureGenerator createSignatureGenerator(
      Map<String, String> comparatorParams, SignatureGeneratorType... supportedTypes) {
    String configuredType = comparatorParams.get(Constants.SIGNATURE_GENERATOR_TYPE);
    if (configuredType == null || configuredType.trim().isEmpty()) {
      return null;
    }
    SignatureGeneratorType type;
    try {
      type = SignatureGeneratorType.valueOf(configuredType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ComparatorCreationError(
          String.format("Unsupported signature generator type (%s).", configuredType));
    }
    for (SignatureGeneratorType supportedType : supportedTypes) {
      if (type == supportedType) {
        return SignatureGeneratorFactory.createSignatureGenerator(type);
      }
    }
    throw new ComparatorCreationError(
        String.format(
            "Signature generator type %s is not supported by this comparator.", configuredType));
  }
}
