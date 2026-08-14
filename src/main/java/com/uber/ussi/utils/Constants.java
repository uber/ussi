/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.utils;

public final class Constants {
  public static final double UNSET_UNI_VALUE = -Double.MAX_VALUE;
  public static final int NUM_SIGNATURES_PER_ID = 270;
  public static final String SIGNATURE_GENERATOR_TYPE = "signature_generator_type";
  public static final String MAX_FRACTION_IDS_PER_SPARSE_KEY = "max_fraction_ids_per_sparse_key";
  public static final String MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE =
      "max_fraction_ids_per_sparse_key_confidence";
  public static final String FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION =
      "full_reevaluation_cache_size_decrease_fraction";
  public static final double DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY = 1.0;
  public static final double DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE = 0.95;
  public static final double DEFAULT_FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION = 0.10;

  private Constants() {}
}
