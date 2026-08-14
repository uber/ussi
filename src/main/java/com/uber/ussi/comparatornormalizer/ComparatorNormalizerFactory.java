/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.ComparatorNormalizerCreationError;
import java.util.Locale;
import java.util.Map;

public class ComparatorNormalizerFactory {

  private ComparatorNormalizerFactory() {}

  public enum COMPARATOR_NORMALIZER_TYPE {
    IDENTITY,
    LP,
    RECIPROCAL
  }

  public static ComparatorNormalizer createComparatorNormalizer(
      String comparatorNormalizerType, Map<String, String> comparatorNormalizerParams)
      throws ComparatorNormalizerCreationError {
    String comparatorNormalizerTypeLowerCase = comparatorNormalizerType.toLowerCase(Locale.ROOT);
    if (comparatorNormalizerTypeLowerCase.equals(
        COMPARATOR_NORMALIZER_TYPE.IDENTITY.name().toLowerCase(Locale.ROOT))) {
      return new IdentityComparatorNormalizer();
    } else if (comparatorNormalizerTypeLowerCase.equals(
        COMPARATOR_NORMALIZER_TYPE.LP.name().toLowerCase(Locale.ROOT))) {
      return new LpComparatorNormalizer();
    } else if (comparatorNormalizerTypeLowerCase.equals(
        COMPARATOR_NORMALIZER_TYPE.RECIPROCAL.name().toLowerCase(Locale.ROOT))) {
      return new ReciprocalComparatorNormalizer();
    }
    throw new ComparatorNormalizerCreationError(
        String.format("Unsupported ComparatorNormalizer type (%s).", comparatorNormalizerType));
  }
}
