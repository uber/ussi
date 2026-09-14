/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory.COMPARATOR_NORMALIZER_TYPE;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Reports the comparator params that {@link ComparatorFactory} would reject. */
public final class ComparatorConfigValidator implements NamespaceConfigValidator {
  private static final ComparatorConfigValidator INSTANCE = new ComparatorConfigValidator();

  private ComparatorConfigValidator() {}

  public static ComparatorConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    collectComparatorNormalizerTypeViolations(config, violations);
    // Every check below asks what a named comparator supports, which an unknown name cannot answer.
    if (!ComparatorFactory.isSupportedComparatorType(config.getComparatorType())) {
      violations.add(
          String.format("Unsupported comparator type (%s).", config.getComparatorType()));
      return;
    }
    collectSequenceDistanceTypeViolations(config, violations);
    collectSignatureGeneratorTypeViolations(config, violations);
  }

  /** A comparator scores through its normalizer, so an unknown one leaves no comparator. */
  private static void collectComparatorNormalizerTypeViolations(
      NamespaceConfig config, List<String> violations) {
    String rawType = config.getComparatorNormalizerType();
    if (rawType.isEmpty() || COMPARATOR_NORMALIZER_TYPE.fromParamValue(rawType) != null) {
      return;
    }
    violations.add(
        String.format(
            "Unsupported comparatorNormalizerType (%s). Supported values: %s.",
            rawType,
            Arrays.stream(COMPARATOR_NORMALIZER_TYPE.values())
                .map(COMPARATOR_NORMALIZER_TYPE::getParamValue)
                .collect(Collectors.joining(", "))));
  }

  private static void collectSequenceDistanceTypeViolations(
      NamespaceConfig config, List<String> violations) {
    String rawType = config.getComparatorParam(Constants.SEQUENCE_DISTANCE_TYPE);
    if (rawType == null || rawType.trim().isEmpty()) {
      return;
    }
    if (!ComparatorFactory.isSequenceComparatorType(config.getComparatorType())) {
      violations.add(
          String.format(
              "%s does not compare sequences, so it has no sequence distance type.",
              config.getComparatorType().toUpperCase(Locale.ROOT)));
      return;
    }
    try {
      ComparatorFactory.createSequenceDistance(config.getComparatorParams());
    } catch (ComparatorCreationError e) {
      violations.add(e.getMessage());
    }
  }

  private static void collectSignatureGeneratorTypeViolations(
      NamespaceConfig config, List<String> violations) {
    String rawType = config.getComparatorParam(Constants.SIGNATURE_GENERATOR_TYPE);
    if (rawType == null || rawType.trim().isEmpty()) {
      return;
    }
    Set<SignatureGeneratorType> supportedTypes =
        ComparatorFactory.getSupportedSignatureGeneratorTypes(config.getComparatorType());
    if (supportedTypes.isEmpty()) {
      violations.add(
          String.format(
              "%s does not support signature generation.",
              config.getComparatorType().toUpperCase(Locale.ROOT)));
      return;
    }
    SignatureGeneratorType signatureGeneratorType;
    try {
      signatureGeneratorType =
          SignatureGeneratorType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      violations.add(String.format("Unsupported signature generator type (%s).", rawType));
      return;
    }
    if (!supportedTypes.contains(signatureGeneratorType)) {
      violations.add(
          String.format(
              "Signature generator type %s is not supported by this comparator.", rawType));
    }
  }
}
