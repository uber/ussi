/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.utils.parallel.ParallelRowScan;
import com.uber.ussi.searchablestructure.utils.scan.ComparatorRowScan;
import com.uber.ussi.searchablestructure.utils.search.SearchRequests;
import java.util.List;

/** Generic writable cache implemented with a full scan search. */
public final class ScanCache extends Cache {

  public ScanCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborRowNumsLocked(
      int numResults, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    return search(record, metadataFilter, minSimilarity, numResults);
  }

  @Override
  protected List<RowNumAndSimilarity> getSimilarRowNumsLocked(
      float minSimilarity,
      LongTermsAndValues record,
      MetaFilter metadataFilter,
      int maxResults) {
    return search(record, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    List<RowNumAndSimilarity> empty =
        SearchRequests.emptyResultsIfNothingToSearch(maxResults, rowNumToTermsAndValuesMap.size());
    if (empty != null) {
      return empty;
    }
    return ParallelRowScan.search(
        rowNumToTermsAndValuesMap,
        record,
        getNumThreadsPerSearch(),
        maxResults,
        minSimilarity,
        (rowNum, termsAndValues, rows, sharedMinSimilarity) -> {
          if (!doesMatchMetaFilter(rowNum, metadataFilter)) {
            return;
          }
          ComparatorRowScan.tryAddSimilarRow(
              rows,
              rowNum,
              record,
              termsAndValues,
              comparator,
              minSimilarity,
              sharedMinSimilarity);
        });
  }
}
