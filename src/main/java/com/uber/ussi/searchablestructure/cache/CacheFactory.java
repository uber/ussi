/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.CacheCreationError;
import java.util.Locale;
import java.util.Objects;

/** Factory for the ERD cache new(config) API. */
public final class CacheFactory {

  /** The cache structures a namespace can be configured with. */
  public enum CacheType {
    /** Sequential scan that scores every row through the comparator. */
    SCAN,

    /** Mutable inverted lists keyed by the terms of the record itself. */
    INVERTED_TERM
  }

  private CacheFactory() {}

  public static Cache createCache(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String cacheType = namespaceConfig.getCacheType().toLowerCase(Locale.ROOT);
    if (cacheType.equals(CacheType.SCAN.name().toLowerCase(Locale.ROOT))) {
      return new ScanCache(namespaceConfig);
    }
    if (cacheType.equals(CacheType.INVERTED_TERM.name().toLowerCase(Locale.ROOT))) {
      return new InvertedTermCache(namespaceConfig);
    }

    throw new CacheCreationError(String.format("Unsupported cache type (%s).", cacheType));
  }
}
