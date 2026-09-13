/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;
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
