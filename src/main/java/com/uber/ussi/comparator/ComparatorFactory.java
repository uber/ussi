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
    return switch (type) {
      case L2 -> {
        rejectSignatureGeneration(type, comparatorParams);
        yield new L2Comparator(comparatorNormalizer);
      }
      case JACCARD ->
          new JaccardComparator(
              comparatorNormalizer, createSignatureGenerator(comparatorParams, type));
      case RUZICKA ->
          new RuzickaComparator(
              comparatorNormalizer, createSignatureGenerator(comparatorParams, type));
      case GLD ->
          new GldComparator(
              comparatorNormalizer,
              createSequenceDistance(comparatorParams),
              createSignatureGenerator(comparatorParams, type));
      case NGLD ->
          new NgldComparator(
              comparatorNormalizer,
              createSequenceDistance(comparatorParams),
              createSignatureGenerator(comparatorParams, type));
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

  private static void rejectSignatureGeneration(
      ComparatorType comparatorType, Map<String, String> comparatorParams) {
    if (hasSignatureGeneratorType(comparatorParams)) {
      throw new ComparatorCreationError(
          String.format("%s does not support signature generation.", comparatorType.name()));
    }
  }

  private static boolean hasSignatureGeneratorType(Map<String, String> comparatorParams) {
    return NamespaceConfigParams.getParam(comparatorParams, Constants.SIGNATURE_GENERATOR)
        != null;
  }

  @Nullable
  private static SignatureGenerator createSignatureGenerator(
      Map<String, String> comparatorParams, ComparatorType comparatorType) {
    String configuredType =
        NamespaceConfigParams.getParam(comparatorParams, Constants.SIGNATURE_GENERATOR);
    if (configuredType == null || configuredType.trim().isEmpty()) {
      return null;
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
    return SignatureGeneratorFactory.createSignatureGenerator(type);
  }
}
