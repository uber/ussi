/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.ComparatorNormalizerCreationError;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

public class ComparatorNormalizerFactory {

  private ComparatorNormalizerFactory() {}

  /** The normalizers a namespace can be configured with. */
  public enum COMPARATOR_NORMALIZER_TYPE {
    COMPLEMENT,
    IDENTITY,
    LP,
    RECIPROCAL;

    /** Returns the normalizer of {@code paramValue}, or null if no normalizer has that name. */
    @Nullable
    public static COMPARATOR_NORMALIZER_TYPE fromParamValue(String paramValue) {
      String normalizedParamValue = paramValue.trim().toLowerCase(Locale.ROOT);
      for (COMPARATOR_NORMALIZER_TYPE type : values()) {
        if (type.getParamValue().equals(normalizedParamValue)) {
          return type;
        }
      }
      return null;
    }

    public String getParamValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public static ComparatorNormalizer createComparatorNormalizer(
      String comparatorNormalizerType, Map<String, String> comparatorNormalizerParams)
      throws ComparatorNormalizerCreationError {
    COMPARATOR_NORMALIZER_TYPE type =
        COMPARATOR_NORMALIZER_TYPE.fromParamValue(comparatorNormalizerType);
    if (type == null) {
      throw new ComparatorNormalizerCreationError(
          String.format("Unsupported ComparatorNormalizer type (%s).", comparatorNormalizerType));
    }
    return switch (type) {
      case COMPLEMENT -> new ComplementComparatorNormalizer();
      case IDENTITY -> new IdentityComparatorNormalizer();
      case LP -> new LpComparatorNormalizer();
      case RECIPROCAL -> new ReciprocalComparatorNormalizer();
    };
  }
}
