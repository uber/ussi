package com.uber.ussi.searchablestructure.utils.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRequestsTest {

  @Test
  void requirePositiveKRejectsNonPositiveK() {
    assertThrows(IllegalArgumentException.class, () -> SearchRequests.requirePositiveK(0));
  }

  @Test
  void requireMinSimilarityInUnitIntervalRejectsOutOfRangeValues() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRequests.requireMinSimilarityInUnitInterval(-0.1f));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRequests.requireMinSimilarityInUnitInterval(1.1f));
  }

  @Test
  void nearestNeighborResultLimitCapsKAtMaxNumSimilarities() {
    NamespaceConfig config = namespaceConfigWithMaxNumSimilarities(5);
    assertEquals(5, SearchRequests.nearestNeighborResultLimit(config, 10));
    assertEquals(3, SearchRequests.nearestNeighborResultLimit(config, 3));
  }

  @Test
  void emptyResultsIfNothingToSearchReturnsEmptyOrNull() {
    List<Object> empty = SearchRequests.emptyResultsIfNothingToSearch(0, 100);
    assertTrue(empty.isEmpty());
    assertNull(SearchRequests.emptyResultsIfNothingToSearch(10, 100));
  }

  private static NamespaceConfig namespaceConfigWithMaxNumSimilarities(int maxNumSimilarities) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType("jaccard")
        .comparatorNormalizerType("complement")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(maxNumSimilarities)
        .build();
  }
}
