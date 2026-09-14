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
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigParams;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public class ComparatorFactory {

  private ComparatorFactory() {}

  public enum COMPARATOR_TYPE {
    GLD,
    JACCARD,
    L2,
    NGLD,
    RUZICKA
  }

  public static boolean isSupportedComparatorType(String comparatorType) {
    if (comparatorType == null) {
      return false;
    }
    for (COMPARATOR_TYPE supportedType : COMPARATOR_TYPE.values()) {
      if (supportedType.name().equalsIgnoreCase(comparatorType.trim())) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether the comparator type compares sequences of elements rather than values. */
  static boolean isSequenceComparatorType(String comparatorType) {
    String trimmedComparatorType = comparatorType.trim();
    return COMPARATOR_TYPE.GLD.name().equalsIgnoreCase(trimmedComparatorType)
        || COMPARATOR_TYPE.NGLD.name().equalsIgnoreCase(trimmedComparatorType);
  }

  /**
   * Returns the signature generators the comparator type accepts, empty when it cannot generate
   * signatures at all. This is the single source of truth for both creation and config validation.
   */
  public static Set<SignatureGeneratorType> getSupportedSignatureGeneratorTypes(
      String comparatorType) {
    String trimmedComparatorType = comparatorType.trim();
    if (COMPARATOR_TYPE.JACCARD.name().equalsIgnoreCase(trimmedComparatorType)) {
      return Set.of(SignatureGeneratorType.MINHASH);
    }
    if (COMPARATOR_TYPE.RUZICKA.name().equalsIgnoreCase(trimmedComparatorType)) {
      return Set.of(
          SignatureGeneratorType.I2CWS,
          SignatureGeneratorType.ICWS,
          SignatureGeneratorType.PCWS,
          SignatureGeneratorType.SCWS);
    }
    return Set.of();
  }

  public static Comparator createComparator(
      String comparatorType,
      Map<String, String> comparatorParams,
      ComparatorNormalizer comparatorNormalizer)
      throws ComparatorCreationError {
    String comparatorTypeLowerCase = comparatorType.toLowerCase(Locale.ROOT);
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.L2.name().toLowerCase(Locale.ROOT))) {
      if (hasSignatureGeneratorType(comparatorParams)) {
        throw new ComparatorCreationError("L2 does not support signature generation.");
      }
      return new L2Comparator(comparatorNormalizer);
    }
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.JACCARD.name().toLowerCase(Locale.ROOT))) {
      return new JaccardComparator(
          comparatorNormalizer,
          createSignatureGenerator(comparatorParams, comparatorTypeLowerCase));
    }
    if (comparatorTypeLowerCase.equals(COMPARATOR_TYPE.RUZICKA.name().toLowerCase(Locale.ROOT))) {
      return new RuzickaComparator(
          comparatorNormalizer,
          createSignatureGenerator(comparatorParams, comparatorTypeLowerCase));
    }
    if (isSequenceComparatorType(comparatorTypeLowerCase)) {
      if (hasSignatureGeneratorType(comparatorParams)) {
        throw new ComparatorCreationError(
            String.format(
                "%s does not support signature generation.",
                comparatorTypeLowerCase.toUpperCase(Locale.ROOT)));
      }
      SequenceDistance sequenceDistance = createSequenceDistance(comparatorParams);
      return comparatorTypeLowerCase.equals(COMPARATOR_TYPE.GLD.name().toLowerCase(Locale.ROOT))
          ? new GldComparator(comparatorNormalizer, sequenceDistance)
          : new NgldComparator(comparatorNormalizer, sequenceDistance);
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

  private static boolean hasSignatureGeneratorType(Map<String, String> comparatorParams) {
    return NamespaceConfigParams.getParam(comparatorParams, Constants.SIGNATURE_GENERATOR_TYPE)
        != null;
  }

  @Nullable
  private static SignatureGenerator createSignatureGenerator(
      Map<String, String> comparatorParams, String comparatorType) {
    String configuredType =
        NamespaceConfigParams.getParam(comparatorParams, Constants.SIGNATURE_GENERATOR_TYPE);
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
    if (!getSupportedSignatureGeneratorTypes(comparatorType).contains(type)) {
      throw new ComparatorCreationError(
          String.format(
              "Signature generator type %s is not supported by this comparator.", configuredType));
    }
    return SignatureGeneratorFactory.createSignatureGenerator(type);
  }
}
