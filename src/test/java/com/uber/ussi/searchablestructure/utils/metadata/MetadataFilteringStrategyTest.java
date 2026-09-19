package com.uber.ussi.searchablestructure.utils.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.uber.ussi.config.ConfigVocabulary;
import org.junit.jupiter.api.Test;

class MetadataFilteringStrategyTest {

  @Test
  void paramValuesAreUnderscoreSeparated() {
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, "in_filtering"));
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, "pre_filtering"));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, " Post_Filtering "));
  }

  @Test
  void aHyphenatedParamValueHasNoStrategy() {
    assertNull(ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, "post-filtering"));
  }
}
