/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.metadata;

import java.util.Locale;

/** Metadata filtering strategy used by a searchable structure's search module. */
public enum MetadataFilteringStrategy {
  AUTO,
  IN_FILTERING,
  PRE_FILTERING,
  POST_FILTERING;

  public static MetadataFilteringStrategy fromIndexParam(String value) {
    String normalizedValue = value.trim().toUpperCase(Locale.ROOT);
    return MetadataFilteringStrategy.valueOf(normalizedValue);
  }
}
