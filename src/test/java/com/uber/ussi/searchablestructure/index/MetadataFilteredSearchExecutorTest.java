package com.uber.ussi.searchablestructure.index;

import static com.uber.ussi.TestLongObjectMaps.longHashSet;
import static com.uber.ussi.TestLongObjectMaps.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataFilteredSearchExecutorTest {
  private static final MetaFilter FILTER = new MetaFilter(Map.of("city", List.of("sf")));

  @Test
  void unfilteredSearchBypassesMetadataResolvers() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.AUTO,
            MetadataFilteringStrategy.IN_FILTERING,
            /* autoAttemptPreFiltering */ true,
            MetadataFilteringStrategy.POST_FILTERING,
            filter -> fail("pre-filtering should not run without a metadata filter"),
            (maxResults, filter) -> fail("post-filter expansion should not run"),
            (rowNum, filter) -> fail("metadata matcher should not run"));

    List<RowNumAndSimilarity> result =
        executor.search(
            MetaFilter.empty(),
            2,
            (metadataFilter, maxResults) -> {
              assertNull(metadataFilter);
              assertEquals(2, maxResults);
              return List.of(row(10), row(11));
            },
            (candidateRowNums, metadataFilter, maxResults) ->
                fail("candidate scorer should not run"));

    assertEquals(List.of(10L, 11L), rowNums(result));
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  @Test
  void autoUsesPreFilteringWhenResolverSucceeds() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.AUTO,
            MetadataFilteringStrategy.POST_FILTERING,
            /* autoAttemptPreFiltering */ true,
            MetadataFilteringStrategy.POST_FILTERING,
            filter -> {
              assertSame(FILTER, filter);
              return PreFilteringResult.success(longHashSet(10, 12));
            },
            (maxResults, filter) -> fail("post-filter expansion should not run"),
            (rowNum, filter) -> fail("metadata matcher should not run"));

    List<RowNumAndSimilarity> result =
        executor.search(
            FILTER,
            1,
            (metadataFilter, maxResults) -> fail("all-rows scorer should not run"),
            (candidateRowNums, metadataFilter, maxResults) -> {
              assertEquals(List.of(10L, 12L), sortedKeys(candidateRowNums));
              assertNull(metadataFilter);
              assertEquals(1, maxResults);
              return List.of(row(12));
            });

    assertEquals(List.of(12L), rowNums(result));
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  @Test
  void autoCanResolveDirectlyToFallbackStrategy() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.AUTO,
            MetadataFilteringStrategy.IN_FILTERING,
            /* autoAttemptPreFiltering */ false,
            MetadataFilteringStrategy.IN_FILTERING,
            filter -> fail("pre-filtering should not run"),
            (maxResults, filter) -> fail("post-filter expansion should not run"),
            (rowNum, filter) -> fail("metadata matcher should not run"));

    List<RowNumAndSimilarity> result =
        executor.search(
            FILTER,
            2,
            (metadataFilter, maxResults) -> {
              assertSame(FILTER, metadataFilter);
              assertEquals(2, maxResults);
              return List.of(row(10), row(11));
            },
            (candidateRowNums, metadataFilter, maxResults) ->
                fail("candidate scorer should not run"));

    assertEquals(List.of(10L, 11L), rowNums(result));
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  @Test
  void preFilteringFallsBackWhenResolverFails() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.PRE_FILTERING,
            MetadataFilteringStrategy.IN_FILTERING,
            /* autoAttemptPreFiltering */ true,
            MetadataFilteringStrategy.IN_FILTERING,
            filter -> PreFilteringResult.failure(),
            (maxResults, filter) -> fail("post-filter expansion should not run"),
            (rowNum, filter) -> fail("metadata matcher should not run"));

    List<RowNumAndSimilarity> result =
        executor.search(
            FILTER,
            2,
            (metadataFilter, maxResults) -> {
              assertSame(FILTER, metadataFilter);
              assertEquals(2, maxResults);
              return List.of(row(10));
            },
            (candidateRowNums, metadataFilter, maxResults) ->
                fail("candidate scorer should not run"));

    assertEquals(List.of(10L), rowNums(result));
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  @Test
  void postFilteringExpandsSearchAndKeepsBestMatchingRows() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.POST_FILTERING,
            MetadataFilteringStrategy.POST_FILTERING,
            /* autoAttemptPreFiltering */ false,
            MetadataFilteringStrategy.POST_FILTERING,
            filter -> fail("pre-filtering should not run"),
            (maxResults, filter) -> {
              assertEquals(1, maxResults);
              assertSame(FILTER, filter);
              return 3;
            },
            (rowNum, filter) -> {
              assertSame(FILTER, filter);
              return rowNum != 10;
            });

    List<RowNumAndSimilarity> result =
        executor.search(
            FILTER,
            1,
            (metadataFilter, maxResults) -> {
              assertNull(metadataFilter);
              assertEquals(3, maxResults);
              return List.of(row(10), row(11), row(12));
            },
            (candidateRowNums, metadataFilter, maxResults) ->
                fail("candidate scorer should not run"));

    assertEquals(List.of(12L), rowNums(result));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  @Test
  void postFilteringReturnsEmptyWhenExpansionIsZero() {
    MetadataFilteredSearchExecutor executor =
        newExecutor(
            MetadataFilteringStrategy.POST_FILTERING,
            MetadataFilteringStrategy.POST_FILTERING,
            /* autoAttemptPreFiltering */ false,
            MetadataFilteringStrategy.POST_FILTERING,
            filter -> fail("pre-filtering should not run"),
            (maxResults, filter) -> 0,
            (rowNum, filter) -> fail("metadata matcher should not run"));

    List<RowNumAndSimilarity> result =
        executor.search(
            FILTER,
            1,
            (metadataFilter, maxResults) -> fail("all-rows scorer should not run"),
            (candidateRowNums, metadataFilter, maxResults) ->
                fail("candidate scorer should not run"));

    assertTrue(result.isEmpty());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        executor.getResolvedMetadataFilteringStrategyForLastSearch());
  }

  private static MetadataFilteredSearchExecutor newExecutor(
      MetadataFilteringStrategy configuredStrategy,
      MetadataFilteringStrategy resolvedStrategyForUnfilteredSearch,
      boolean autoAttemptPreFiltering,
      MetadataFilteringStrategy fallbackStrategyWhenPreFilteringUnavailable,
      MetadataFilteredSearchExecutor.PreFilteringResolver preFilteringResolver,
      MetadataFilteredSearchExecutor.PostFilteringMaxResultsResolver
          postFilteringMaxResultsResolver,
      MetadataFilteredSearchExecutor.MetadataMatcher metadataMatcher) {
    return new MetadataFilteredSearchExecutor(
        configuredStrategy,
        resolvedStrategyForUnfilteredSearch,
        autoAttemptPreFiltering,
        fallbackStrategyWhenPreFilteringUnavailable,
        preFilteringResolver,
        postFilteringMaxResultsResolver,
        metadataMatcher);
  }

  private static RowNumAndSimilarity row(long rowNum) {
    return new RowNumAndSimilarity(rowNum, rowNum / 100.0f);
  }

  private static List<Long> rowNums(List<RowNumAndSimilarity> rows) {
    return rows.stream().map(RowNumAndSimilarity::getRowNum).toList();
  }
}
