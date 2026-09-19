/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.metadata;

import com.uber.ussi.config.ConfigVocabulary;

/** Metadata filtering strategy used by a searchable structure's search module. */
public enum MetadataFilteringStrategy implements ConfigVocabulary {
  AUTO,
  IN_FILTERING,
  PRE_FILTERING,
  POST_FILTERING
}
