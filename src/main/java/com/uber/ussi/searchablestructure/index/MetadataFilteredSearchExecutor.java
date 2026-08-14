/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.carrotsearch.hppc.LongHashSet;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Resolves metadata filtering strategy and delegates actual row scoring to an index. */
public final class MetadataFilteredSearchExecutor {
  private final MetadataFilteringStrategy configuredStrategy;
  private final MetadataFilteringStrategy resolvedStrategyForUnfilteredSearch;
  private final boolean autoAttemptPreFiltering;
  private final MetadataFilteringStrategy fallbackStrategyWhenPreFilteringUnavailable;
  private final PreFilteringResolver preFilteringResolver;
  private final PostFilteringMaxResultsResolver postFilteringMaxResultsResolver;
  private final MetadataMatcher metadataMatcher;
  private MetadataFilteringStrategy resolvedMetadataFilteringStrategyForLastSearch;

  public MetadataFilteredSearchExecutor(
      MetadataFilteringStrategy configuredStrategy,
      MetadataFilteringStrategy resolvedStrategyForUnfilteredSearch,
      boolean autoAttemptPreFiltering,
      MetadataFilteringStrategy fallbackStrategyWhenPreFilteringUnavailable,
      PreFilteringResolver preFilteringResolver,
      PostFilteringMaxResultsResolver postFilteringMaxResultsResolver,
      MetadataMatcher metadataMatcher) {
    this.configuredStrategy = Objects.requireNonNull(configuredStrategy, "configuredStrategy");
    this.resolvedStrategyForUnfilteredSearch =
        Objects.requireNonNull(
            resolvedStrategyForUnfilteredSearch, "resolvedStrategyForUnfilteredSearch");
    this.autoAttemptPreFiltering = autoAttemptPreFiltering;
    this.fallbackStrategyWhenPreFilteringUnavailable =
        Objects.requireNonNull(
            fallbackStrategyWhenPreFilteringUnavailable,
            "fallbackStrategyWhenPreFilteringUnavailable");
    this.preFilteringResolver =
        Objects.requireNonNull(preFilteringResolver, "preFilteringResolver");
    this.postFilteringMaxResultsResolver =
        Objects.requireNonNull(postFilteringMaxResultsResolver, "postFilteringMaxResultsResolver");
    this.metadataMatcher = Objects.requireNonNull(metadataMatcher, "metadataMatcher");
    this.resolvedMetadataFilteringStrategyForLastSearch = resolvedStrategyForUnfilteredSearch;
  }

  public List<RowNumAndSimilarity> search(
      MetaFilter metadataFilter,
      int maxResults,
      AllRowsScorer allRowsScorer,
      CandidateRowsScorer candidateRowsScorer) {
    Objects.requireNonNull(allRowsScorer, "allRowsScorer");
    Objects.requireNonNull(candidateRowsScorer, "candidateRowsScorer");
    if (!hasMetadataFilter(metadataFilter)) {
      resolvedMetadataFilteringStrategyForLastSearch = resolvedStrategyForUnfilteredSearch;
      return allRowsScorer.score(/* metadataFilter */ null, maxResults);
    }

    return switch (configuredStrategy) {
      case AUTO -> {
        if (autoAttemptPreFiltering) {
          yield searchWithPreFilteringOrFallback(
              metadataFilter, maxResults, allRowsScorer, candidateRowsScorer);
        }
        yield searchWithResolvedStrategy(
            fallbackStrategyWhenPreFilteringUnavailable, metadataFilter, maxResults, allRowsScorer);
      }
      case IN_FILTERING ->
          searchWithResolvedStrategy(
              MetadataFilteringStrategy.IN_FILTERING, metadataFilter, maxResults, allRowsScorer);
      case PRE_FILTERING ->
          searchWithPreFilteringOrFallback(
              metadataFilter, maxResults, allRowsScorer, candidateRowsScorer);
      case POST_FILTERING ->
          searchWithResolvedStrategy(
              MetadataFilteringStrategy.POST_FILTERING, metadataFilter, maxResults, allRowsScorer);
    };
  }

  public MetadataFilteringStrategy getResolvedMetadataFilteringStrategyForLastSearch() {
    return resolvedMetadataFilteringStrategyForLastSearch;
  }

  private List<RowNumAndSimilarity> searchWithPreFilteringOrFallback(
      MetaFilter metadataFilter,
      int maxResults,
      AllRowsScorer allRowsScorer,
      CandidateRowsScorer candidateRowsScorer) {
    PreFilteringResult preFilteringResult = preFilteringResolver.resolve(metadataFilter);
    if (preFilteringResult.isSuccess()) {
      resolvedMetadataFilteringStrategyForLastSearch = MetadataFilteringStrategy.PRE_FILTERING;
      return candidateRowsScorer.score(
          preFilteringResult.getRowNums(), /* metadataFilter */ null, maxResults);
    }
    return searchWithResolvedStrategy(
        fallbackStrategyWhenPreFilteringUnavailable, metadataFilter, maxResults, allRowsScorer);
  }

  private List<RowNumAndSimilarity> searchWithResolvedStrategy(
      MetadataFilteringStrategy resolvedStrategy,
      MetaFilter metadataFilter,
      int maxResults,
      AllRowsScorer allRowsScorer) {
    resolvedMetadataFilteringStrategyForLastSearch = resolvedStrategy;
    // resolvedStrategy is only ever IN_FILTERING or POST_FILTERING by construction.
    if (resolvedStrategy == MetadataFilteringStrategy.POST_FILTERING) {
      return postFilteringSearch(metadataFilter, maxResults, allRowsScorer);
    }
    return allRowsScorer.score(metadataFilter, maxResults);
  }

  private List<RowNumAndSimilarity> postFilteringSearch(
      MetaFilter metadataFilter, int maxResults, AllRowsScorer allRowsScorer) {
    int postFilteringMaxResults =
        postFilteringMaxResultsResolver.resolve(maxResults, metadataFilter);
    if (postFilteringMaxResults == 0) {
      return Collections.emptyList();
    }
    List<RowNumAndSimilarity> unfilteredResult =
        allRowsScorer.score(/* metadataFilter */ null, postFilteringMaxResults);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    for (RowNumAndSimilarity row : unfilteredResult) {
      if (metadataMatcher.matches(row.getRowNum(), metadataFilter)) {
        rows.add(row);
      }
    }
    return rows.toList();
  }

  private static boolean hasMetadataFilter(MetaFilter metadataFilter) {
    return metadataFilter != null && !metadataFilter.isEmpty();
  }

  @FunctionalInterface
  public interface AllRowsScorer {
    List<RowNumAndSimilarity> score(@Nullable MetaFilter metadataFilter, int maxResults);
  }

  @FunctionalInterface
  public interface CandidateRowsScorer {
    List<RowNumAndSimilarity> score(
        LongHashSet candidateRowNums, @Nullable MetaFilter metadataFilter, int maxResults);
  }

  @FunctionalInterface
  public interface MetadataMatcher {
    boolean matches(long rowNum, MetaFilter metadataFilter);
  }

  @FunctionalInterface
  public interface PostFilteringMaxResultsResolver {
    int resolve(int maxResults, MetaFilter metadataFilter);
  }

  @FunctionalInterface
  public interface PreFilteringResolver {
    PreFilteringResult resolve(MetaFilter metadataFilter);
  }
}
