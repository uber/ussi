/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/** Hybrid inverted index using exact keys for short rows and signatures for long rows. */
public final class HybridIndex extends Index {

  /**
   * The largest term count a row can have and still be keyed by its own terms. Above it a row is
   * keyed by signatures instead.
   *
   * <p>It is the signature count because that is where signatures stop being a saving: a row with
   * fewer terms than that would be replaced by more signatures than it had terms, costing list
   * entries and buying no pruning. The two quantities are derived separately and happen to
   * coincide, so the cutoff names itself rather than reading as a signature count here.
   */
  private static final int TERM_KEYING_CUTOFF = SignatureIndex.NUM_SIGNATURES_PER_ROW;

  private final TermIndex termIndex;
  private final SignatureIndex signatureIndex;
  private final boolean termPopularityFilteringEnabled;

  public HybridIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig);
    LongObjectHashMap<LongTermsAndValues> exactRows = new LongObjectHashMap<>();
    LongObjectHashMap<LongTermsAndValues> signatureRows = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (entry.value.termsLength() <= TERM_KEYING_CUTOFF) {
        exactRows.put(entry.key, entry.value);
      } else {
        signatureRows.put(entry.key, entry.value);
      }
    }
    // Only the term index discards. What discarding buys is shorter inverted lists, and only a
    // term index can collect it: a signature list holds one entry per row whatever that row's
    // terms are, so discarding leaves a signature index's lists exactly as long and only moves the
    // signatures its rows are keyed by.
    LongHashSet discardedTerms = BaseInvertedIndex.discardedTermsOf(namespaceConfig, exactRows);
    // The signature index is built first so a comparator without a generator is rejected before
    // the term index is populated.
    this.signatureIndex =
        new SignatureIndex(namespaceConfig, signatureRows, rowNumToMetaMap, new LongHashSet());
    this.termIndex = new TermIndex(namespaceConfig, exactRows, rowNumToMetaMap, discardedTerms);
    this.termPopularityFilteringEnabled = termIndex.discardsPopularTerms();
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    boolean queryUsesExactIndex = record.termsLength() <= TERM_KEYING_CUTOFF;
    Index firstIndex = queryUsesExactIndex ? termIndex : signatureIndex;
    Index secondIndex = queryUsesExactIndex ? signatureIndex : termIndex;
    boolean secondIndexIsExact = !queryUsesExactIndex;

    List<RowNumAndSimilarity> firstResults =
        firstIndex.size() == 0
            ? List.of()
            : firstIndex.getNearestNeighborRowNums(
                maxResults, record, metadataFilter, minSimilarity);
    List<RowNumAndSimilarity> secondResults = List.of();
    if (secondIndex.size() > 0) {
      if (firstResults.size() < maxResults) {
        secondResults =
            secondIndex.getNearestNeighborRowNums(
                maxResults, record, metadataFilter, minSimilarity);
      } else {
        // The second index need not score a row the first index already beats k times over. Asked
        // for its own best few above that floor, it also raises a floor of its own as it goes.
        float secondFloor =
            Math.max(minSimilarity, getConservativeMinSimilarity(firstResults));
        if (maySearchIndex(secondIndexIsExact, record, secondFloor)) {
          secondResults =
              secondIndex.getNearestNeighborRowNums(
                  maxResults, record, metadataFilter, secondFloor);
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
            ? termIndex.getSimilarRowNums(minSimilarity, record, metadataFilter)
            : List.of();
    List<RowNumAndSimilarity> signatureResults =
        maySearchIndex(/* exactIndex */ false, record, minSimilarity)
            ? signatureIndex.getSimilarRowNums(minSimilarity, record, metadataFilter)
            : List.of();
    return mergeResults(exactResults, signatureResults, namespaceConfig.getMaxNumSimilarities());
  }

  @Override
  public boolean delete(long rowNum) {
    // A row is held by whichever index keys rows of its length, and a delete carries no
    // record to tell which that is. The term index is therefore asked first, and the
    // signature index only if it did not hold the row.
    return termIndex.delete(rowNum) || signatureIndex.delete(rowNum);
  }

  @Override
  public LongObjectHashMap<LongTermsAndValues> getAll() {
    LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>(size());
    rows.putAll(termIndex.getAll());
    rows.putAll(signatureIndex.getAll());
    return rows;
  }

  @Override
  public LongObjectHashMap<LongMeta> getAllMetadata() {
    LongObjectHashMap<LongMeta> metadata = new LongObjectHashMap<>(size());
    metadata.putAll(termIndex.getAllMetadata());
    metadata.putAll(signatureIndex.getAllMetadata());
    return metadata;
  }

  @Override
  public int size() {
    return termIndex.size() + signatureIndex.size();
  }

  @Override
  public boolean isEmpty() {
    return termIndex.isEmpty() && signatureIndex.isEmpty();
  }

  @Override
  public void close() {
    termIndex.close();
    signatureIndex.close();
  }

  int getNumExactRowsForTests() {
    return termIndex.size();
  }

  int getNumSignatureRowsForTests() {
    return signatureIndex.size();
  }

  long[] getExactDiscardedTermsForTests() {
    return termIndex.getDiscardedTermsForTests();
  }

  long[] getSignatureDiscardedTermsForTests() {
    return signatureIndex.getDiscardedTermsForTests();
  }

  private boolean maySearchIndex(
      boolean exactIndex, LongTermsAndValues query, double minSimilarity) {
    Index index = exactIndex ? termIndex : signatureIndex;
    if (index.size() == 0) {
      return false;
    }
    if (termPopularityFilteringEnabled) {
      return true;
    }
    int minNumTerms = exactIndex ? 0 : TERM_KEYING_CUTOFF + 1;
    int maxNumTerms = exactIndex ? TERM_KEYING_CUTOFF : Integer.MAX_VALUE;
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
