/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Collections;
import java.util.List;

/** Generic writable cache implemented with a sequential scan search. */
public final class GenericCache extends Cache {

  public GenericCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborsLocked(
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
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      long rowNum = entry.key;
      if (!matchesMetaFilter(rowNum, metadataFilter)) {
        continue;
      }
      float similarity = (float) comparator.getSimilarity(record, entry.value, minSimilarity);
      if (similarity >= minSimilarity) {
        rows.add(new RowNumAndSimilarity(rowNum, similarity));
      }
    }
    return rows.toList();
  }
}
