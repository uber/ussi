/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;

/** Reports the cache types and cache params that no cache could be built from. */
public final class CacheConfigValidator implements NamespaceConfigValidator {
  private static final CacheConfigValidator INSTANCE = new CacheConfigValidator();

  private CacheConfigValidator() {}

  public static CacheConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    CacheType cacheType = ConfigVocabulary.fromParamValue(CacheType.class, config.getCacheType());
    if (cacheType == null) {
      collectCacheTypeViolations(config, violations);
      return;
    }
    if (cacheType != CacheType.INVERTED_TERM) {
      return;
    }
    // The inverted term cache keys lists by the record's own terms and reads a value per term, so
    // it stores order-agnostic sparse records.
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

  /** A cacheType that is non-blank and names no structure is invalid. */
  private static void collectCacheTypeViolations(NamespaceConfig config, List<String> violations) {
    if (config.getCacheType().isEmpty()) {
      return;
    }
    violations.add(
        ConfigVocabulary.unsupported("cacheType", config.getCacheType(), CacheType.class));
  }
}
