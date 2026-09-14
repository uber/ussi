/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * An enum a namespace config names one constant of, each written as its lower-case constant name.
 * A name must be resolved the same way wherever it is read, or a config is validated against one
 * set of names and built from another.
 */
public interface ConfigVocabulary {
  /** Returns the constant name, which every enum already provides. */
  String name();

  /** Returns the name this constant is written as in a config. */
  default String getParamValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Returns the constant {@code paramValue} names, ignoring case and padding, or null when it is
   * blank or names nothing in {@code vocabulary}.
   */
  @Nullable
  static <E extends Enum<E> & ConfigVocabulary> E fromParamValue(
      Class<E> vocabulary, @Nullable String paramValue) {
    if (NamespaceConfigParams.isBlank(paramValue)) {
      return null;
    }
    String normalizedParamValue = paramValue.trim().toLowerCase(Locale.ROOT);
    for (E value : vocabulary.getEnumConstants()) {
      if (value.getParamValue().equals(normalizedParamValue)) {
        return value;
      }
    }
    return null;
  }

  /** Returns the names a config can write, in declaration order. */
  private static <E extends Enum<E> & ConfigVocabulary> String supportedParamValues(
      Class<E> vocabulary) {
    return Arrays.stream(vocabulary.getEnumConstants())
        .map(ConfigVocabulary::getParamValue)
        .collect(Collectors.joining(", "));
  }

  /** Returns the message for a {@code configName} value that names nothing in its vocabulary. */
  static <E extends Enum<E> & ConfigVocabulary> String unsupported(
      String configName, @Nullable String rawValue, Class<E> vocabulary) {
    return String.format(
        "Unsupported %s (%s). Supported values: %s.",
        configName, rawValue, supportedParamValues(vocabulary));
  }
}
