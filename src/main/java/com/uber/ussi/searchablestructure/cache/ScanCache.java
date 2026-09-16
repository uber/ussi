/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.ScanSplit;
import java.util.Collections;
import java.util.List;

/** Generic writable cache implemented with a full scan search. */
public final class ScanCache extends Cache {

  public ScanCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborRowNumsLocked(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, /* minSimilarity */ 0.0f, numResults);
  }

  @Override
  protected List<RowNumAndSimilarity> getSimilarRowNumsLocked(
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
    return ScanSplit.search(
        rowNumToTermsAndValuesMap,
        record,
        searchParallelism(),
        maxResults,
        (rowNum, termsAndValues, rows) -> {
          if (!matchesMetaFilter(rowNum, metadataFilter)) {
            return;
          }
          float similarity =
              (float) comparator.getSimilarity(record, termsAndValues, minSimilarity);
          if (similarity >= minSimilarity) {
            rows.add(new RowNumAndSimilarity(rowNum, similarity));
          }
        });
  }
}
