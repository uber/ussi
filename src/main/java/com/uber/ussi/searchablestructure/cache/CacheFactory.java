/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.CacheCreationError;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;

/** Factory for the ERD cache new(config) API. */
public final class CacheFactory {

  /** The cache structures a namespace can be configured with. */
  public enum CacheType {
    /** Sequential scan that scores every row through the comparator. */
    SCAN,

    /** Mutable inverted lists keyed by the terms of the record itself. */
    INVERTED_TERM;

    @Nullable
    public static CacheType fromParamValue(String paramValue) {
      String normalizedParamValue = paramValue.trim().toLowerCase(Locale.ROOT);
      for (CacheType cacheType : values()) {
        if (cacheType.getParamValue().equals(normalizedParamValue)) {
          return cacheType;
        }
      }
      return null;
    }

    public String getParamValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private CacheFactory() {}

  public static Cache createCache(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String configuredCacheType = namespaceConfig.getCacheType();
    CacheType cacheType = CacheType.fromParamValue(configuredCacheType);
    if (cacheType == null) {
      throw new CacheCreationError(
          String.format("Unsupported cache type (%s).", configuredCacheType));
    }
    return switch (cacheType) {
      case SCAN -> new ScanCache(namespaceConfig);
      case INVERTED_TERM -> new InvertedTermCache(namespaceConfig);
    };
  }
}
