/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.config.ConfigVocabulary;

/** The normalizers a namespace can be configured with. */
public enum ComparatorNormalizerType implements ConfigVocabulary {
  COMPLEMENT,
  IDENTITY,
  LP,
  RECIPROCAL
}
