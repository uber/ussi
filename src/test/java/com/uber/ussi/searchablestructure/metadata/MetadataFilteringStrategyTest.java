package com.uber.ussi.searchablestructure.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MetadataFilteringStrategyTest {

  @Test
  void fromIndexParamAcceptsUnderscoreSeparatedValues() {
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        MetadataFilteringStrategy.fromIndexParam("in_filtering"));
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        MetadataFilteringStrategy.fromIndexParam("pre_filtering"));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        MetadataFilteringStrategy.fromIndexParam("post_filtering"));
  }

  @Test
  void fromIndexParamRejectsHyphenatedValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MetadataFilteringStrategy.fromIndexParam("post-filtering"));
  }
}
