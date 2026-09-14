/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.CacheCreationError;
import java.util.Objects;

/** Factory for the ERD cache new(config) API. */
public final class CacheFactory {

  private CacheFactory() {}

  public static Cache createCache(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig");

    String configuredCacheType = namespaceConfig.getCacheType();
    CacheType cacheType = ConfigVocabulary.fromParamValue(CacheType.class, configuredCacheType);
    if (cacheType == null) {
      throw new CacheCreationError(
          ConfigVocabulary.unsupported("cacheType", configuredCacheType, CacheType.class));
    }
    return switch (cacheType) {
      case SCAN -> new ScanCache(namespaceConfig);
      case INVERTED_TERM -> new InvertedTermCache(namespaceConfig);
    };
  }
}
