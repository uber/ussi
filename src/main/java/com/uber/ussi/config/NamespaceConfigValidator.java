/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.config;

import java.util.List;

/**
 * Validates the part of a {@link NamespaceConfig} owned by one layer.
 *
 * <p>{@link NamespaceConfig} checks only structural invariants it can see on its own. Every rule
 * that depends on what a layer supports lives with that layer, which keeps the config package free
 * of dependencies on the layers that consume it.
 */
@FunctionalInterface
public interface NamespaceConfigValidator {
  /** Appends one message per violation found in {@code config}, leaving the list alone if valid. */
  void collectViolations(NamespaceConfig config, List<String> violations);
}
