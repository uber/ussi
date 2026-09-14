/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.cache.CacheFactory.CacheType;
import com.uber.ussi.utils.Constants;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Reports the cache types and cache params that no cache could be built from. */
public final class CacheConfigValidator implements NamespaceConfigValidator {
  private static final CacheConfigValidator INSTANCE = new CacheConfigValidator();

  private CacheConfigValidator() {}

  public static CacheConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    CacheType cacheType = CacheType.fromParamValue(config.getCacheType());
    if (cacheType == null) {
      collectCacheTypeViolations(config, violations);
      return;
    }
    if (cacheType != CacheType.INVERTED_TERM) {
      return;
    }
    /*
     * The inverted term cache keys its inverted lists by the record's own terms and reads a value
     * per term, so it stores order-agnostic sparse records. A comparator that cannot read that type
     * caches through the scan cache instead, which scores every row through the comparator.
     */
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator != null
        && !comparator.getSupportedRecordTypes().contains(RecordType.ORDER_AGNOSTIC_SPARSE)) {
      violations.add(
          String.format(
              "cacheType %s stores %s records, which comparatorType %s cannot read.",
              cacheType.getParamValue(),
              RecordType.ORDER_AGNOSTIC_SPARSE.name().toLowerCase(Locale.ROOT),
              config.getComparatorType()));
    }
    ConfigViolations.checkDoubleAboveMinInRange(
        violations,
        Constants.MAX_FRACTION_IDS_PER_KEY,
        config.getCacheParam(Constants.MAX_FRACTION_IDS_PER_KEY),
        0.0,
        1.0);
    ConfigViolations.checkDoubleInRange(
        violations,
        Constants.MAX_FRACTION_IDS_PER_KEY_CONFIDENCE,
        config.getCacheParam(Constants.MAX_FRACTION_IDS_PER_KEY_CONFIDENCE),
        0.5,
        1.0);
    ConfigViolations.checkDoubleInRange(
        violations,
        Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
        config.getCacheParam(Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION),
        0.0,
        1.0);
  }

  /**
   * A name that is not one of the cache structures is reported here rather than left to {@link
   * CacheFactory}, which only refuses it once a namespace is being opened. A blank name is the
   * config's own structural check and is not repeated.
   */
  private static void collectCacheTypeViolations(NamespaceConfig config, List<String> violations) {
    if (config.getCacheType().isEmpty()) {
      return;
    }
    violations.add(
        String.format(
            "Unsupported cacheType (%s). Supported values: %s.",
            config.getCacheType(),
            Arrays.stream(CacheType.values())
                .map(CacheType::getParamValue)
                .collect(Collectors.joining(", "))));
  }
}
