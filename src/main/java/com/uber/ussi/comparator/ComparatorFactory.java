/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparator.sequencedistance.SequenceDistanceFactory;
import com.uber.ussi.comparator.sequencedistance.SequenceDistanceFactory.SequenceDistanceType;
import com.uber.ussi.comparator.signaturegenerator.SignatureGenerator;
import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory;
import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigParams;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.Map;
import javax.annotation.Nullable;

public class ComparatorFactory {

  private ComparatorFactory() {}

  public static Comparator createComparator(
      String comparatorType,
      Map<String, String> comparatorParams,
      ComparatorNormalizer comparatorNormalizer)
      throws ComparatorCreationError {
    ComparatorType type = ConfigVocabulary.fromParamValue(ComparatorType.class, comparatorType);
    if (type == null) {
      throw new ComparatorCreationError(
          ConfigVocabulary.unsupported("comparatorType", comparatorType, ComparatorType.class));
    }
    validateSignatureGeneration(comparatorParams, type);
    return switch (type) {
      case L2 -> new L2Comparator(comparatorNormalizer);
      case JACCARD -> new JaccardComparator(comparatorNormalizer);
      case RUZICKA -> new RuzickaComparator(comparatorNormalizer);
      case GLD ->
          new GldComparator(comparatorNormalizer, createSequenceDistance(comparatorParams));
      case NGLD ->
          new NgldComparator(comparatorNormalizer, createSequenceDistance(comparatorParams));
    };
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

  /**
   * Returns the configured comparator, or null when the config does not describe one that can be
   * created. Validators use this so that a comparator that cannot be created is reported once, by
   * {@link ComparatorConfigValidator}, rather than once per check that could not be run.
   */
  @Nullable
  public static Comparator tryCreateComparator(NamespaceConfig namespaceConfig) {
    try {
      return createComparator(namespaceConfig);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Returns the configured edit distance, defaulting to {@link SequenceDistanceType#LEVENSHTEIN}
   * when the param is absent.
   */
  static SequenceDistance createSequenceDistance(Map<String, String> comparatorParams)
      throws ComparatorCreationError {
    String configuredType =
        NamespaceConfigParams.getParam(comparatorParams, Constants.SEQUENCE_DISTANCE_TYPE);
    if (configuredType == null || configuredType.trim().isEmpty()) {
      return SequenceDistanceFactory.createSequenceDistance(SequenceDistanceType.LEVENSHTEIN);
    }
    try {
      return SequenceDistanceFactory.createSequenceDistance(configuredType);
    } catch (IllegalArgumentException e) {
      throw new ComparatorCreationError(e.getMessage());
    }
  }

  /**
   * Returns the configured signature generator, or null when the comparator params name none.
   *
   * <p>The comparator does not hold one: the same comparator serves a structure that keys by
   * signatures and one that has none, so the structure that needs signatures creates this.
   */
  @Nullable
  public static SignatureGenerator createSignatureGenerator(NamespaceConfig namespaceConfig)
      throws ComparatorCreationError {
    ComparatorType comparatorType =
        ConfigVocabulary.fromParamValue(ComparatorType.class, namespaceConfig.getComparatorType());
    if (comparatorType == null) {
      throw new ComparatorCreationError(
          ConfigVocabulary.unsupported(
              "comparatorType", namespaceConfig.getComparatorType(), ComparatorType.class));
    }
    SignatureGeneratorType type =
        resolveSignatureGeneratorType(namespaceConfig.getComparatorParams(), comparatorType);
    return type == null ? null : SignatureGeneratorFactory.createSignatureGenerator(type);
  }

  /**
   * Rejects a signature generator the comparator cannot have. Creating the comparator does not
   * create the generator, but a param naming one this comparator cannot supply is a config error
   * whatever structure the namespace ends up with, so it is reported when the param is read.
   */
  private static void validateSignatureGeneration(
      Map<String, String> comparatorParams, ComparatorType comparatorType) {
    resolveSignatureGeneratorType(comparatorParams, comparatorType);
  }

  /** Returns the configured generator type, or null when the params name none. */
  @Nullable
  private static SignatureGeneratorType resolveSignatureGeneratorType(
      Map<String, String> comparatorParams, ComparatorType comparatorType) {
    String configuredType =
        NamespaceConfigParams.getParam(comparatorParams, Constants.SIGNATURE_GENERATOR);
    if (configuredType == null || configuredType.trim().isEmpty()) {
      return null;
    }
    if (comparatorType.getSupportedSignatureGeneratorTypes().isEmpty()) {
      throw new ComparatorCreationError(
          String.format("%s does not support signature generation.", comparatorType.name()));
    }
    SignatureGeneratorType type =
        ConfigVocabulary.fromParamValue(SignatureGeneratorType.class, configuredType);
    if (type == null) {
      throw new ComparatorCreationError(
          ConfigVocabulary.unsupported(
              Constants.SIGNATURE_GENERATOR, configuredType, SignatureGeneratorType.class));
    }
    if (!comparatorType.getSupportedSignatureGeneratorTypes().contains(type)) {
      throw new ComparatorCreationError(
          String.format(
              "Signature generator type %s is not supported by this comparator.", configuredType));
    }
    return type;
  }
}
