/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.List;

/**
 * Validates the part of a {@link NamespaceConfig} owned by one layer. Rules that depend on what a
 * layer supports live with that layer, keeping the config package free of dependencies on it.
 */
@FunctionalInterface
public interface NamespaceConfigValidator {
  /** Appends one message per violation found in {@code config}, leaving the list alone if valid. */
  void collectViolations(NamespaceConfig config, List<String> violations);
}
