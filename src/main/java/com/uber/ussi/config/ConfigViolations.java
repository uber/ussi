/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Violation checks shared by {@link NamespaceConfig} and the layer validators. */
public final class ConfigViolations {
  private ConfigViolations() {}

  public static void checkNonBlank(List<String> violations, String name, @Nullable String value) {
    if (NamespaceConfigParams.isBlank(value)) {
      violations.add(name + " must be a non-blank string.");
    }
  }

  public static void checkPositive(List<String> violations, String name, int value) {
    if (value <= 0) {
      violations.add(name + " must be > 0, got " + value + ".");
    }
  }

  public static void checkAtMost(List<String> violations, String name, int value, int ceiling) {
    if (value > ceiling) {
      violations.add(name + " must be <= " + ceiling + ", got " + value + ".");
    }
  }

  public static void checkNonNegative(List<String> violations, String name, int value) {
    if (value < 0) {
      violations.add(name + " must be >= 0, got " + value + ".");
    }
  }

  /** Checks that {@code rawValue}, when set, parses to a double in [minValue, maxValue]. */
  public static void checkDoubleInRange(
      List<String> violations,
      String name,
      @Nullable String rawValue,
      double minValue,
      double maxValue) {
    checkDouble(violations, name, rawValue, minValue, maxValue, /* minValueExcluded */ false);
  }

  /** Checks that {@code rawValue}, when set, parses to a double in (minValue, maxValue]. */
  public static void checkDoubleAboveMinInRange(
      List<String> violations,
      String name,
      @Nullable String rawValue,
      double minValue,
      double maxValue) {
    checkDouble(violations, name, rawValue, minValue, maxValue, /* minValueExcluded */ true);
  }

  /**
   * Checks that every key in {@code params} is one {@code recognizedKeys} names. A key is matched
   * the way {@link NamespaceConfigParams#getParam getParam()} matches it, so a key reported here
   * is one no layer would have read, and a key accepted here is one some layer reads.
   */
  public static void checkNoUnknownKeys(
      List<String> violations,
      String paramsName,
      Map<String, String> params,
      Set<String> recognizedKeys) {
    for (String key : params.keySet()) {
      if (key != null && recognizedKeys.contains(key.trim().toLowerCase(Locale.ROOT))) {
        continue;
      }
      violations.add(
          String.format(
              "Unknown %s key (%s). %s",
              paramsName, key, describeRecognizedKeys(recognizedKeys)));
    }
  }

  /** Renders violations as a single message, listing them when there is more than one. */
  public static String format(List<String> violations) {
    if (violations.size() == 1) {
      return violations.get(0);
    }
    StringBuilder message =
        new StringBuilder("NamespaceConfig has ").append(violations.size()).append(" violations:");
    for (String violation : violations) {
      message.append("\n  - ").append(violation);
    }
    return message.toString();
  }

  private static String describeRecognizedKeys(Set<String> recognizedKeys) {
    if (recognizedKeys.isEmpty()) {
      return "It reads no keys.";
    }
    return "Supported keys: "
        + recognizedKeys.stream().sorted().collect(Collectors.joining(", "))
        + ".";
  }

  private static void checkDouble(
      List<String> violations,
      String name,
      @Nullable String rawValue,
      double minValue,
      double maxValue,
      boolean minValueExcluded) {
    if (NamespaceConfigParams.isBlank(rawValue)) {
      return;
    }
    String range =
        String.format(minValueExcluded ? "(%s, %s]" : "[%s, %s]", minValue, maxValue);
    double value;
    try {
      value = Double.parseDouble(rawValue.trim());
    } catch (NumberFormatException e) {
      violations.add(String.format("%s must be a double in %s.", name, range));
      return;
    }
    boolean isAboveMinValue = minValueExcluded ? value > minValue : value >= minValue;
    if (!isAboveMinValue || value > maxValue) {
      violations.add(String.format("%s must be in %s, got %s.", name, range, value));
    }
  }
}
