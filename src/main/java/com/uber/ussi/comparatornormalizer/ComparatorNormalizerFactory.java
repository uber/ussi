/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.error.ComparatorNormalizerCreationError;
import java.util.Map;

public class ComparatorNormalizerFactory {

  private ComparatorNormalizerFactory() {}

  public static ComparatorNormalizer createComparatorNormalizer(
      String comparatorNormalizerType, Map<String, String> comparatorNormalizerParams)
      throws ComparatorNormalizerCreationError {
    ComparatorNormalizerType type =
        ConfigVocabulary.fromParamValue(ComparatorNormalizerType.class, comparatorNormalizerType);
    if (type == null) {
      throw new ComparatorNormalizerCreationError(
          ConfigVocabulary.unsupported(
              "comparatorNormalizerType", comparatorNormalizerType,
              ComparatorNormalizerType.class));
    }
    return switch (type) {
      case COMPLEMENT -> new ComplementComparatorNormalizer();
      case IDENTITY -> new IdentityComparatorNormalizer();
      case LP -> new LpComparatorNormalizer();
      case RECIPROCAL -> new ReciprocalComparatorNormalizer();
    };
  }
}
