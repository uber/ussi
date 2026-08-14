package com.uber.ussi.entity.meta;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetaFilterTest {

  private static LongMeta meta(Map<String, String> rawMetadata) {
    return new LongMeta(rawMetadata, /* requireLongKeysAndValues */ false);
  }

  @Test
  void emptyFilterMatchesEverything() {
    MetaFilter filter = MetaFilter.empty();
    assertTrue(filter.isEmpty());
    assertTrue(filter.doesMatch(meta(Map.of("city", "sf"))));
    assertTrue(filter.doesMatch(LongMeta.empty()));
  }

  @Test
  void matchesAnyValueWithinKey() {
    // ERD: "These filterKey-level sets are unioned for each metadataKey" (OR within a key).
    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf", "la")));
    assertTrue(filter.doesMatch(meta(Map.of("city", "sf"))));
    assertTrue(filter.doesMatch(meta(Map.of("city", "la"))));
    assertFalse(filter.doesMatch(meta(Map.of("city", "nyc"))));
  }

  @Test
  void requiresEveryKeyToMatch() {
    // ERD: "These metadataKey-level sets are intersected" (AND across keys).
    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf"), "country", List.of("us")));
    assertTrue(filter.doesMatch(meta(Map.of("city", "sf", "country", "us"))));
    // Missing the "country" key breaks the intersection.
    assertFalse(filter.doesMatch(meta(Map.of("city", "sf"))));
  }

  @Test
  void filterKeysAreCaseInsensitive() {
    MetaFilter filter = new MetaFilter(Map.of("City", List.of("SF")));
    assertTrue(filter.doesMatch(meta(Map.of("city", "sf"))));
  }

  @Test
  void swappedKeyAndValueDoesNotMatch() {
    // Guards against the symmetric-hash bug: a filter for key "b" = "a" must not match {"a":"b"}.
    MetaFilter filter = new MetaFilter(Map.of("b", List.of("a")));
    assertFalse(filter.doesMatch(meta(Map.of("a", "b"))));
    assertTrue(filter.doesMatch(meta(Map.of("b", "a"))));
  }
}
