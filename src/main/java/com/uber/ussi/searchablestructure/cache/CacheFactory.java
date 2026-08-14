/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.CacheCreationError;
import java.util.Locale;
import java.util.Objects;

/** Factory for the ERD cache new(config) API. */
public final class CacheFactory {

  public enum CacheType {
    GENERIC,
    SPARSE
  }

  private CacheFactory() {}

  public static Cache createCache(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String cacheType = namespaceConfig.getCacheType().toLowerCase(Locale.ROOT);
    if (cacheType.equals(CacheType.GENERIC.name().toLowerCase(Locale.ROOT))) {
      return new GenericCache(namespaceConfig);
    }
    if (cacheType.equals(CacheType.SPARSE.name().toLowerCase(Locale.ROOT))) {
      return new SparseCache(namespaceConfig);
    }

    throw new CacheCreationError(String.format("Unsupported cache type (%s).", cacheType));
  }
}
