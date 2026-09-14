/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.utils;

/**
 * Constants shared across layers. A param key lives here when more than one layer reads it; keys
 * owned by a single layer stay on that layer, such as {@code Index.MAX_PRE_FILTERING_ROWS_RATIO}.
 */
public final class Constants {
  public static final double UNSET_UNI_VALUE = -Double.MAX_VALUE;
  public static final int NUM_SIGNATURES_PER_ID = 270;
  public static final String SIGNATURE_GENERATOR = "signature_generator";
  public static final String SEQUENCE_DISTANCE_TYPE = "sequence_distance_type";
  public static final String MAX_FRACTION_IDS_PER_TERM = "max_fraction_ids_per_term";
  public static final String MAX_FRACTION_IDS_PER_TERM_CONFIDENCE =
      "max_fraction_ids_per_term_confidence";
  public static final String FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION =
      "full_reevaluation_cache_size_decrease_fraction";
  public static final String CANDIDATE_GENERATOR = "candidate_generator";
  public static final String POPULAR_TERM_DISCARD_SCOPE = "popular_term_discard_scope";
  public static final double DEFAULT_MAX_FRACTION_IDS_PER_TERM = 1.0;
  public static final double DEFAULT_MAX_FRACTION_IDS_PER_TERM_CONFIDENCE = 0.95;
  public static final double DEFAULT_FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION = 0.10;

  private Constants() {}
}
