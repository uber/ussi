package com.uber.ussi;

import static com.uber.ussi.TestLongObjectMaps.rowNums;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.utils.Constants;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SparseHybridEndToEndTest {
  private static final float DELTA = 1e-6f;

  @Test
  void hybridResultsRemainStableAcrossSparseCacheGraduation() {
    TermsAndValues exactRecord = sparseRecord(270);
    TermsAndValues signatureRecord = sparseRecord(271);
    try (NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config(2))) {
      long exactRow = index.insert(exactRecord, Map.of("city", "sf"));
      long signatureRow = index.insert(signatureRecord, Map.of("city", "la"));

      assertHybridResults(index, signatureRecord, signatureRow, exactRow);
      index.awaitBackgroundTasks();
      assertHybridResults(index, signatureRecord, signatureRow, exactRow);
      assertEquals(
          List.of(exactRow),
          rowNums(
              index
                  .getNearestNeighbors(
                      2, signatureRecord, new MetaFilter(Map.of("city", List.of("sf"))))
                  .getRowNums()));

      assertTrue(index.delete(signatureRow));
      assertEquals(List.of(exactRow), rowNums(index.getNearestNeighbors(2, signatureRecord, null)));
    }
  }

  @Test
  void hybridRowsRemainSearchableAfterIndexConsolidation() {
    TermsAndValues exactRecord = sparseRecord(270);
    TermsAndValues signatureRecord = sparseRecord(271);
    try (NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config(1))) {
      long exactRow1 = index.insert(exactRecord, Map.of());
      long signatureRow1 = index.insert(signatureRecord, Map.of());
      long exactRow2 = index.insert(exactRecord, Map.of());
      long signatureRow2 = index.insert(signatureRecord, Map.of());

      index.awaitBackgroundTasks();

      assertTrue(index.getNumSearchableStructures() <= 3);
      assertEquals(
          List.of(signatureRow1, signatureRow2, exactRow1, exactRow2),
          rowNums(index.getNearestNeighbors(4, signatureRecord, null)));
      assertEquals(
          List.of(exactRow1, signatureRow1, exactRow2, signatureRow2),
          rowNums(index.getAllRowNums()));
    }
  }

  private static void assertHybridResults(
      NearestNeighborSearchIndex index, TermsAndValues query, long signatureRow, long exactRow) {
    SearchResults nearest = index.getNearestNeighbors(2, query, null);

    assertEquals(List.of(signatureRow, exactRow), rowNums(nearest));
    assertEquals(1.0f, nearest.getSimilarity(0), DELTA);
    assertEquals(270.0f / 271.0f, nearest.getSimilarity(1), DELTA);
    assertEquals(List.of(signatureRow), rowNums(index.getSimilarRowNums(1.0f, query, null)));
  }

  private static NamespaceConfig config(int maxCacheSize) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(1)
        .maxTermsAndValuesLength(300)
        .maxCacheSize(maxCacheSize)
        .cacheType("sparse")
        .indexType("sparse")
        .comparatorType("jaccard")
        .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash"))
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static TermsAndValues sparseRecord(int numTerms) {
    String[] terms = new String[numTerms];
    float[] values = new float[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      terms[i] = "term-" + i;
    }
    Arrays.fill(values, 1f);
    return new TermsAndValues(terms, values);
  }
}
