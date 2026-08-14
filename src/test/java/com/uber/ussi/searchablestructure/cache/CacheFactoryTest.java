package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.CacheCreationError;
import org.junit.jupiter.api.Test;

class CacheFactoryTest {

  @Test
  void createCacheCreatesGenericCache() {
    Cache cache = CacheFactory.createCache(validConfig().cacheType("GENERIC").build());

    assertInstanceOf(GenericCache.class, cache);
  }

  @Test
  void createCacheCreatesSparseCache() {
    Cache cache = CacheFactory.createCache(validConfig().cacheType("SPARSE").build());

    assertInstanceOf(SparseCache.class, cache);
  }

  @Test
  void createCacheRejectsUnsupportedCacheType() {
    NamespaceConfig config = validConfig().cacheType("hnsw").build();

    assertThrows(CacheCreationError.class, () -> CacheFactory.createCache(config));
  }

  private static NamespaceConfig.Builder validConfig() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(10)
        .cacheType("generic")
        .indexType("dense")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10);
  }
}
