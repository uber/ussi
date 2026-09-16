/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.scan;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.ScanSplit;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Generic delete-only index implemented with a full scan search. */
public final class ScanIndex extends Index {
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
            this::matchesMetaFilter);
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, /* minSimilarity */ 0.0f, numResults);
  }

  @Override
  public List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return search(record, metadataFilter, minSimilarity, namespaceConfig.getMaxNumSimilarities());
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    if (maxResults == 0 || rowNumToTermsAndValuesMap.isEmpty()) {
      return Collections.emptyList();
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
    return ScanSplit.search(
        rowNumToTermsAndValuesMap,
        requestTermsAndValues,
        searchParallelism(),
        maxResults,
        (rowNum, termsAndValues, rows) ->
            addMatchingRow(
                rows,
                rowNum,
                termsAndValues,
                requestTermsAndValues,
                metadataFilter,
                minSimilarity));
  }

  private List<RowNumAndSimilarity> searchRowNums(
      LongHashSet rowNums,
      LongTermsAndValues requestTermsAndValues,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    return ScanSplit.searchCandidates(
        rowNums,
        requestTermsAndValues,
        searchParallelism(),
        maxResults,
        (rowNum, rows) ->
            addMatchingRow(
                rows,
                rowNum,
                Objects.requireNonNull(rowNumToTermsAndValuesMap.get(rowNum)),
                requestTermsAndValues,
                metadataFilter,
                minSimilarity));
  }

  private void addMatchingRow(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      long rowNum,
      LongTermsAndValues termsAndValues,
      LongTermsAndValues requestTermsAndValues,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity) {
    if (isDeleted(rowNum)) {
      return;
    }
    if (metadataFilter != null && !matchesMetaFilter(rowNum, metadataFilter)) {
      return;
    }
    float similarity =
        (float) comparator.getSimilarity(requestTermsAndValues, termsAndValues, minSimilarity);
    if (similarity >= minSimilarity) {
      rows.add(new RowNumAndSimilarity(rowNum, similarity));
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }
}
