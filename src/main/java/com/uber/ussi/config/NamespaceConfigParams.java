/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Param-map lookup shared by {@link NamespaceConfig}, {@link ConfigViolations}, and the factories
 * that read a param map they were handed rather than the config it came from. A param has to be
 * found the same way wherever it is read, or a config can be validated on a value that the layer
 * building from it never sees.
 */
public final class NamespaceConfigParams {
  private NamespaceConfigParams() {}

  /** Returns the value for {@code key}, matching case-insensitively and ignoring padding. */
  @Nullable
  public static String getParam(Map<String, String> params, String key) {
    String value = params.get(key);
    if (value != null) {
      return value;
    }
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(key)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** Returns the double at {@code key}, or {@code defaultValue} when unset. */
  static double readDoubleParam(Map<String, String> params, String key, double defaultValue) {
    String value = getParam(params, key);
    return isBlank(value) ? defaultValue : Double.parseDouble(value.trim());
  }

  static boolean isBlank(@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }
}
