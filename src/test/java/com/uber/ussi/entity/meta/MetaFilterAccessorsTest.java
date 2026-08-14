package com.uber.ussi.entity.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetaFilterAccessorsTest {

  @Test
  void emptyFilterHasNoEntries() {
    assertTrue(MetaFilter.empty().isEmpty());
  }

  @Test
  void getValuesForEachKeyReturnsNormalizedValues() {
    MetaFilter filter = new MetaFilter(Map.of("City", List.of("SF", "LA")));

    Map<String, List<String>> values = filter.getValuesForEachKey(Set.of("city"));

    assertEquals(List.of("sf", "la"), values.get("city"));
  }

  @Test
  void getValuesForEachKeyThrowsForUnknownKey() {
    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf")));

    assertThrows(NoSuchElementException.class, () -> filter.getValuesForEachKey(Set.of("country")));
  }
}
