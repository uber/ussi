/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.ConfigVocabulary;

/** The cache structures a namespace can be configured with. */
public enum CacheType implements ConfigVocabulary {
  /** Sequential scan that scores every row through the comparator. */
  SCAN,

  /** Mutable inverted lists keyed by the terms of the record itself. */
  INVERTED_TERM
}
