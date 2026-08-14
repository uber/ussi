package com.uber.ussi.searchablestructure.index;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexEdgeCasesTest {

  @Test
  void baseIndexDefaultsAreUsable() {
    TestIndex index = new TestIndex(config(Map.of()), longObjectMap(), null);

    assertTrue(index.isEmpty());
    assertFalse(index.supportsInFilteringForTests());
    assertEquals(5, index.getPostFilteringMaxResultsForTests(5, MetaFilter.empty()));

    index.close();
  }

  @Test
  void postFilteringMaxResultsReturnsZeroWhenNoRowsMatchMetadata() {
    TestIndex index =
        new TestIndex(
            config(Map.of()),
            longObjectMap(1, denseInternal(1f, 0f)),
            longObjectMap(1, longMeta("city", "sf")));

    int maxResults =
        index.getPostFilteringMaxResultsForTests(
            5, new MetaFilter(Map.of("city", List.of("missing"))));

    assertEquals(0, maxResults);
  }

  @Test
  void constructorRejectsInvalidPreFilteringRatioValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TestIndex(
                config(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "not-a-double")),
                longObjectMap(),
                longObjectMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TestIndex(
                config(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "-0.1")),
                longObjectMap(),
                longObjectMap()));
  }

  private static NamespaceConfig config(Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(100)
        .cacheType("generic")
        .indexType("generic")
        .indexParams(indexParams)
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static LongTermsAndValues denseInternal(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += value * value;
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  private static LongMeta longMeta(String key, String value) {
    return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
  }

  private static final class TestIndex extends Index {

    private TestIndex(
        NamespaceConfig namespaceConfig,
        LongObjectHashMap<LongTermsAndValues> rows,
        LongObjectHashMap<LongMeta> metadata) {
      super(namespaceConfig, rows, metadata);
    }

    private boolean supportsInFilteringForTests() {
      return supportsInFiltering();
    }

    private int getPostFilteringMaxResultsForTests(int maxResults, MetaFilter metadataFilter) {
      return getPostFilteringMaxResults(maxResults, metadataFilter);
    }

    @Override
    public List<RowNumAndSimilarity> getNearestNeighbors(
        int k, LongTermsAndValues record, MetaFilter metadataFilter) {
      return Collections.emptyList();
    }

    @Override
    public List<RowNumAndSimilarity> getSimilarRowNums(
        float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
      return Collections.emptyList();
    }
  }
}
