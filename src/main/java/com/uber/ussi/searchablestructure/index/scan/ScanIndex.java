/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.scan;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.RowStoringIndex;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.utils.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.utils.parallel.ParallelRowScan;
import com.uber.ussi.searchablestructure.utils.parallel.SharedMinSimilarity;
import com.uber.ussi.searchablestructure.utils.scan.ComparatorRowScan;
import com.uber.ussi.searchablestructure.utils.search.SearchRequests;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Generic delete-only index implemented with a full scan search. */
public final class ScanIndex extends RowStoringIndex {
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;

  public ScanIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    this.metadataFilteredSearchExecutor =
        new MetadataFilteredSearchExecutor(
            metadataFilteringStrategy,
            MetadataFilteringStrategy.IN_FILTERING,
            /* autoAttemptPreFiltering */ false,
            MetadataFilteringStrategy.IN_FILTERING,
            this::getMatchingRowNumsIfUnderPreFilteringLimit,
            this::getPostFilteringMaxResults,
            this::doesMatchMetaFilter);
  }

  @Override
  protected List<RowNumAndSimilarity> searchNearestNeighbors(
      int numResults, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    return search(record, metadataFilter, minSimilarity, numResults);
  }

  @Override
  protected List<RowNumAndSimilarity> searchSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    return search(
        record,
        metadataFilter,
        minSimilarity,
        SearchRequests.similarSearchResultLimit(namespaceConfig));
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    List<RowNumAndSimilarity> empty =
        SearchRequests.emptyResultsIfNothingToSearch(maxResults, rowNumToTermsAndValuesMap.size());
    if (empty != null) {
      return empty;
    }
    return metadataFilteredSearchExecutor.search(
        metadataFilter,
        maxResults,
        (resolvedMetadataFilter, resolvedMaxResults) ->
            searchAllRows(record, resolvedMetadataFilter, minSimilarity, resolvedMaxResults),
        (candidateRowNums, resolvedMetadataFilter, resolvedMaxResults) ->
            searchRowNums(
                candidateRowNums,
                record,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults));
  }

  MetadataFilteringStrategy getResolvedMetadataFilteringStrategyForLastSearchForTests() {
    return metadataFilteredSearchExecutor.getResolvedMetadataFilteringStrategyForLastSearch();
  }

  private List<RowNumAndSimilarity> searchAllRows(
      LongTermsAndValues requestTermsAndValues,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    return ParallelRowScan.search(
        rowNumToTermsAndValuesMap,
        requestTermsAndValues,
        getNumThreadsPerSearch(),
        maxResults,
        minSimilarity,
        (rowNum, termsAndValues, rows, sharedMinSimilarity) ->
            addMatchingRow(
                rows,
                rowNum,
                termsAndValues,
                requestTermsAndValues,
                metadataFilter,
                minSimilarity,
                sharedMinSimilarity));
  }

  private List<RowNumAndSimilarity> searchRowNums(
      LongHashSet rowNums,
      LongTermsAndValues requestTermsAndValues,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    return ParallelRowScan.searchCandidates(
        rowNums,
        requestTermsAndValues,
        getNumThreadsPerSearch(),
        maxResults,
        minSimilarity,
        (rowNum, rows, sharedMinSimilarity) ->
            addMatchingRow(
                rows,
                rowNum,
                Objects.requireNonNull(rowNumToTermsAndValuesMap.get(rowNum)),
                requestTermsAndValues,
                metadataFilter,
                minSimilarity,
                sharedMinSimilarity));
  }

  private void addMatchingRow(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      long rowNum,
      LongTermsAndValues termsAndValues,
      LongTermsAndValues requestTermsAndValues,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      SharedMinSimilarity sharedMinSimilarity) {
    if (isDeleted(termsAndValues)) {
      return;
    }
    if (metadataFilter != null && !doesMatchMetaFilter(rowNum, metadataFilter)) {
      return;
    }
    ComparatorRowScan.tryAddSimilarRow(
        rows,
        rowNum,
        requestTermsAndValues,
        termsAndValues,
        comparator,
        minSimilarity,
        sharedMinSimilarity);
  }

}
