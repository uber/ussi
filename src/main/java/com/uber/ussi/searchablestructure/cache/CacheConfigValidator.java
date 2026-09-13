/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;

/** Reports the cache params that the configured cache type would reject. */
public final class CacheConfigValidator implements NamespaceConfigValidator {
  private static final CacheConfigValidator INSTANCE = new CacheConfigValidator();

  private CacheConfigValidator() {}

  public static CacheConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    String sparseCacheType = CacheFactory.CacheType.SPARSE.name().toLowerCase(Locale.ROOT);
    if (!sparseCacheType.equals(config.getCacheType())) {
      return;
    }
    ConfigViolations.checkDoubleAboveMinInRange(
        violations,
        Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
        config.getCacheParam(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY),
        0.0,
        1.0);
    ConfigViolations.checkDoubleInRange(
        violations,
        Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE,
        config.getCacheParam(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE),
        0.5,
        1.0);
    ConfigViolations.checkDoubleInRange(
        violations,
        Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
        config.getCacheParam(Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION),
        0.0,
        1.0);
  }
}
