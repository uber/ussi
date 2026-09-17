package com.uber.ussi.searchablestructure.index.inverted.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MergeSearchTest {
  private static final Comparator COMPARATOR = ComparatorFactory.createComparator(config());
  private static final Map<Long, Double> UNI_VALUES = Map.of(1L, 1.0, 2L, 2.0, 3L, 3.0);

  @Test
  void searchScoresFromConjunctionWithoutConsultingTheVerificationLookup() {
    LongTermsAndValues query = jaccard(new long[] {10, 20}, 1, 1);
    MergeSearch.QueryKey[] queryKeys = {
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0),
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0)
    };

    List<RowNumAndSimilarity> results =
        MergeSearch.search(
            COMPARATOR,
            query,
            query,
            null,
            0.0f,
            5,
            queryKeys,
            mergeContext(Map.of(1L, 2.0)),
            (rowNum, metadataFilter) -> true,
            /* scoresFromConjunction */ true,
            rowNum -> {
              throw new AssertionError("verification lookup should not run");
            },
            new SharedFloor(0.0f));

    assertEquals(List.of(1L), rowNums(results));
    assertTrue(results.get(0).getSimilarity() > 0.99f);
  }

  @Test
  void searchVerifiesCandidatesWhenConjunctionScoringIsDisabled() {
    LongTermsAndValues query = jaccard(new long[] {10}, 1);
    Map<Long, LongTermsAndValues> rows = Map.of(1L, jaccard(new long[] {10}, 1));
    MergeSearch.QueryKey[] queryKeys = {
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0)
    };

    List<RowNumAndSimilarity> results =
        MergeSearch.search(
            COMPARATOR,
            query,
            query,
            null,
            0.0f,
            5,
            queryKeys,
            mergeContext(),
            (rowNum, metadataFilter) -> true,
            /* scoresFromConjunction */ false,
            rows::get,
            new SharedFloor(0.0f));

    assertEquals(List.of(1L), rowNums(results));
  }

  /** A row deleted between generation and verification has nothing to score, so it drops out. */
  @Test
  void searchDropsACandidateThatTheVerificationLookupNoLongerHas() {
    LongTermsAndValues query = jaccard(new long[] {10}, 1);
    MergeSearch.QueryKey[] queryKeys = {
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0)
    };

    List<RowNumAndSimilarity> results =
        MergeSearch.search(
            COMPARATOR,
            query,
            query,
            null,
            0.0f,
            5,
            queryKeys,
            mergeContext(),
            (rowNum, metadataFilter) -> true,
            /* scoresFromConjunction */ false,
            rowNum -> null,
            new SharedFloor(0.0f));

    assertEquals(List.of(), rowNums(results));
  }

  @Test
  void searchDoesNotDuplicateRowsWhenTwoCandidatesShareAUniValue() {
    LongTermsAndValues query = jaccard(new long[] {10, 20}, 1, 1);
    MergeSearch.QueryKey[] queryKeys = {
      new MergeSearch.QueryKey(new InvertedList(new long[] {1, 2}, new float[] {1f, 1f}), 1f, 1.0),
      new MergeSearch.QueryKey(new InvertedList(new long[] {3, 4}, new float[] {1f, 1f}), 1f, 1.0)
    };
    Map<Long, Double> tiedUniValues = Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0, 4L, 1.0);

    List<RowNumAndSimilarity> results =
        MergeSearch.search(
            COMPARATOR,
            query,
            query,
            null,
            0.0f,
            4,
            queryKeys,
            mergeContext(tiedUniValues),
            (rowNum, metadataFilter) -> true,
            /* scoresFromConjunction */ true,
            rowNum -> jaccard(new long[] {10}, 1),
            new SharedFloor(0.0f));

    assertEquals(List.of(1L, 2L, 3L, 4L), rowNums(results).stream().sorted().toList());
  }

  private static List<Long> rowNums(List<RowNumAndSimilarity> results) {
    return results.stream().map(RowNumAndSimilarity::getRowNum).toList();
  }

  private static MergeSearch.Context mergeContext() {
    return mergeContext(UNI_VALUES);
  }

  private static MergeSearch.Context mergeContext(Map<Long, Double> uniValues) {
    return new MergeSearch.Context() {
      @Override
      public double getUniValue(long rowNum) {
        return uniValues.get(rowNum);
      }

      @Override
      public double stableSortedUniValue(LongTermsAndValues termsAndValues) {
        return termsAndValues.getUniValue();
      }
    };
  }

  private static LongTermsAndValues jaccard(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(Math.signum(value));
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static NamespaceConfig config() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(10)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("inverted_term")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }
}
