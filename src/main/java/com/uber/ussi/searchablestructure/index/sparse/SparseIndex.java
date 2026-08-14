/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.SignatureComparator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.Constants;
import java.util.List;

/** Hybrid sparse index using exact keys for short rows and signatures for long rows. */
public final class SparseIndex extends Index {
  private final InvertedIndex invertedIndex;
  private final SignatureIndex signatureIndex;
  private final boolean sparseKeyPopularityFilteringEnabled;

  public SparseIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    if (!(comparator instanceof SignatureComparator)
        || !((SignatureComparator) comparator).supportsSignatures()) {
      throw new IndexCreationError(
          "SparseIndex requires a comparator with a configured signature generator.");
    }
    LongObjectHashMap<LongTermsAndValues> exactRows = new LongObjectHashMap<>();
    LongObjectHashMap<LongTermsAndValues> signatureRows = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (entry.value.termsLength() <= Constants.NUM_SIGNATURES_PER_ID) {
        exactRows.put(entry.key, entry.value);
      } else {
        signatureRows.put(entry.key, entry.value);
      }
    }
    this.invertedIndex = new InvertedIndex(namespaceConfig, exactRows, rowNumToMetaMap);
    this.signatureIndex = new SignatureIndex(namespaceConfig, signatureRows, rowNumToMetaMap);
    this.sparseKeyPopularityFilteringEnabled = invertedIndex.hasSparseKeyPopularityFiltering();
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighbors(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    boolean queryUsesExactIndex = record.termsLength() <= Constants.NUM_SIGNATURES_PER_ID;
    Index firstIndex = queryUsesExactIndex ? invertedIndex : signatureIndex;
    Index secondIndex = queryUsesExactIndex ? signatureIndex : invertedIndex;
    boolean secondIndexIsExact = !queryUsesExactIndex;

    List<RowNumAndSimilarity> firstResults =
        firstIndex.size() == 0
            ? List.of()
            : firstIndex.getNearestNeighbors(maxResults, record, metadataFilter);
    List<RowNumAndSimilarity> secondResults = List.of();
    if (secondIndex.size() > 0) {
      if (firstResults.size() < maxResults) {
        secondResults = secondIndex.getNearestNeighbors(maxResults, record, metadataFilter);
      } else {
        float minSimilarity = getConservativeMinSimilarity(firstResults);
        if (maySearchIndex(secondIndexIsExact, record, minSimilarity)) {
          secondResults = secondIndex.getSimilarRowNums(minSimilarity, record, metadataFilter);
        }
      }
    }
    return mergeResults(firstResults, secondResults, maxResults);
  }

  @Override
  public List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    List<RowNumAndSimilarity> exactResults =
        maySearchIndex(/* exactIndex */ true, record, minSimilarity)
            ? invertedIndex.getSimilarRowNums(minSimilarity, record, metadataFilter)
            : List.of();
    List<RowNumAndSimilarity> signatureResults =
        maySearchIndex(/* exactIndex */ false, record, minSimilarity)
            ? signatureIndex.getSimilarRowNums(minSimilarity, record, metadataFilter)
            : List.of();
    return mergeResults(exactResults, signatureResults, namespaceConfig.getMaxNumSimilarities());
  }

  @Override
  protected void onRowDeleted(long rowNum) {
    if (!invertedIndex.delete(rowNum)) {
      signatureIndex.delete(rowNum);
    }
  }

  @Override
  public void close() {
    invertedIndex.close();
    signatureIndex.close();
  }

  int getNumExactRowsForTests() {
    return invertedIndex.size();
  }

  int getNumSignatureRowsForTests() {
    return signatureIndex.size();
  }

  private boolean maySearchIndex(
      boolean exactIndex, LongTermsAndValues query, double minSimilarity) {
    Index index = exactIndex ? invertedIndex : signatureIndex;
    if (index.size() == 0) {
      return false;
    }
    if (sparseKeyPopularityFilteringEnabled) {
      return true;
    }
    int minNumTerms = exactIndex ? 0 : Constants.NUM_SIGNATURES_PER_ID + 1;
    int maxNumTerms = exactIndex ? Constants.NUM_SIGNATURES_PER_ID : Integer.MAX_VALUE;
    return comparator.mayPassNumTermsFiltering(query, minNumTerms, maxNumTerms, minSimilarity);
  }

  private static float getConservativeMinSimilarity(List<RowNumAndSimilarity> results) {
    float minSimilarity = results.get(0).getSimilarity();
    for (int i = 1; i < results.size(); ++i) {
      minSimilarity = Math.min(minSimilarity, results.get(i).getSimilarity());
    }
    return Math.max(0.0f, Math.nextDown(minSimilarity));
  }

  private static List<RowNumAndSimilarity> mergeResults(
      List<RowNumAndSimilarity> exactResults,
      List<RowNumAndSimilarity> signatureResults,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> merged =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    merged.addAll(exactResults);
    merged.addAll(signatureResults);
    return merged.toList();
  }
}
