/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerType;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Set;

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
    ComparatorType comparatorType =
        ConfigVocabulary.fromParamValue(ComparatorType.class, config.getComparatorType());
    if (comparatorType == null) {
      violations.add(
          ConfigVocabulary.unsupported(
              "comparatorType", config.getComparatorType(), ComparatorType.class));
      return;
    }
    collectSequenceDistanceTypeViolations(config, comparatorType, violations);
    collectSignatureGeneratorTypeViolations(config, comparatorType, violations);
  }

  /** A comparator scores through its normalizer, so an unknown one leaves no comparator. */
  private static void collectComparatorNormalizerTypeViolations(
      NamespaceConfig config, List<String> violations) {
    String rawType = config.getComparatorNormalizerType();
    if (rawType.isEmpty()
        || ConfigVocabulary.fromParamValue(ComparatorNormalizerType.class, rawType) != null) {
      return;
    }
    violations.add(
        ConfigVocabulary.unsupported(
            "comparatorNormalizerType", rawType, ComparatorNormalizerType.class));
  }

  private static void collectSequenceDistanceTypeViolations(
      NamespaceConfig config, ComparatorType comparatorType, List<String> violations) {
    String rawType = config.getComparatorParam(Constants.SEQUENCE_DISTANCE_TYPE);
    if (rawType == null || rawType.trim().isEmpty()) {
      return;
    }
    if (!comparatorType.comparesSequences()) {
      violations.add(
          String.format(
              "%s does not compare sequences, so it has no sequence distance type.",
              comparatorType.name()));
      return;
    }
    try {
      ComparatorFactory.createSequenceDistance(config.getComparatorParams());
    } catch (ComparatorCreationError e) {
      violations.add(e.getMessage());
    }
  }

  private static void collectSignatureGeneratorTypeViolations(
      NamespaceConfig config, ComparatorType comparatorType, List<String> violations) {
    String rawType = config.getComparatorParam(Constants.SIGNATURE_GENERATOR_TYPE);
    if (rawType == null || rawType.trim().isEmpty()) {
      return;
    }
    Set<SignatureGeneratorType> supportedTypes =
        comparatorType.getSupportedSignatureGeneratorTypes();
    if (supportedTypes.isEmpty()) {
      violations.add(
          String.format("%s does not support signature generation.", comparatorType.name()));
      return;
    }
    SignatureGeneratorType signatureGeneratorType =
        ConfigVocabulary.fromParamValue(SignatureGeneratorType.class, rawType);
    if (signatureGeneratorType == null) {
      violations.add(
          ConfigVocabulary.unsupported(
              Constants.SIGNATURE_GENERATOR_TYPE, rawType, SignatureGeneratorType.class));
      return;
    }
    if (!supportedTypes.contains(signatureGeneratorType)) {
      violations.add(
          String.format(
              "Signature generator type %s is not supported by this comparator.", rawType));
    }
  }
}
