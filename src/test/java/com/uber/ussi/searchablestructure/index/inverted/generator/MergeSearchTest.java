package com.uber.ussi.searchablestructure.index.inverted.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.comparator.ConjunctionScored;
import com.uber.ussi.comparator.KeyShareBounded;
import com.uber.ussi.comparatornormalizer.ComplementComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.IdentityComparatorNormalizer;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.utils.parallel.SharedMinSimilarity;
import com.uber.ussi.utils.ConfigKeys;
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
            /* mergeScoresFromAccumulatedConjunction */ true,
            rowNum -> {
              throw new AssertionError("verification lookup should not run");
            },
            new SharedMinSimilarity(0.0f),
            partialConjunctionPolicy(/* mergeScoresFromAccumulatedConjunction */ true));

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
            /* mergeScoresFromAccumulatedConjunction */ false,
            rows::get,
            new SharedMinSimilarity(0.0f),
            partialConjunctionPolicy(/* mergeScoresFromAccumulatedConjunction */ false));

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
            /* mergeScoresFromAccumulatedConjunction */ false,
            rowNum -> null,
            new SharedMinSimilarity(0.0f),
            partialConjunctionPolicy(/* mergeScoresFromAccumulatedConjunction */ false));

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
            /* mergeScoresFromAccumulatedConjunction */ true,
            rowNum -> jaccard(new long[] {10}, 1),
            new SharedMinSimilarity(0.0f),
            partialConjunctionPolicy(/* mergeScoresFromAccumulatedConjunction */ true));

    assertEquals(List.of(1L, 2L, 3L, 4L), rowNums(results).stream().sorted().toList());
  }

  @Test
  void sequenceMergePolicyMapsMinimumSimilarityToSharedKeyFraction() {
    Comparator ngld = ngldComparator();
    KeyShareBounded keyShareBound = (KeyShareBounded) ngld;
    MergeSearch.PartialConjunctionPolicy policy =
        MergeSearch.PartialConjunctionPolicy.forSequenceIndexedMultisetMerge(
            keyShareBound,
            ngld.getComparatorNormalizer(),
            ComparatorFactory.createIndexedMultisetMergeConjunctionScored());

    assertTrue(policy.hasCustomMinSimilarityForConjunction());
    assertTrue(policy.doesUsePartialConjunction());
    double queryUniValue = 10.0;
    double minNormalizedSimilarity = 0.5;
    assertEquals(
        keyShareBound.minMultisetSimilarityForMergePartialConjunction(
            ngld.getComparatorNormalizer(), queryUniValue, minNormalizedSimilarity),
        policy.minSimilarityForPartialConjunction(queryUniValue, minNormalizedSimilarity),
        EPSILON_9);
  }

  /**
   * A sequence comparator is not {@link ConjunctionScored}, yet merge still bounds rows with an
   * indexed multiset conjunction and verifies through the configured measure.
   */
  @Test
  void searchVerifiesSequenceCandidatesWithIndexedMultisetPartialConjunction() {
    Comparator ngld = ngldComparator();
    LongTermsAndValues orderedQuery = sequence(1, 2, 3);
    LongTermsAndValues indexedQuery =
        LongTermsAndValuesTestFactory.create(
            new long[] {1, 2, 3}, new float[] {1f, 1f, 1f}, 3.0);
    Map<Long, LongTermsAndValues> rows = Map.of(1L, orderedQuery);
    MergeSearch.QueryKey[] queryKeys = {
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0),
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0),
      new MergeSearch.QueryKey(new InvertedList(new long[] {1}, new float[] {1f}), 1f, 1.0)
    };
    MergeSearch.PartialConjunctionPolicy policy =
        MergeSearch.PartialConjunctionPolicy.forSequenceIndexedMultisetMerge(
            (KeyShareBounded) ngld,
            ngld.getComparatorNormalizer(),
            ComparatorFactory.createIndexedMultisetMergeConjunctionScored());

    List<RowNumAndSimilarity> results =
        MergeSearch.search(
            ngld,
            orderedQuery,
            indexedQuery,
            null,
            0.0f,
            5,
            queryKeys,
            mergeContext(Map.of(1L, 3.0)),
            (rowNum, metadataFilter) -> true,
            /* mergeScoresFromAccumulatedConjunction */ false,
            rows::get,
            new SharedMinSimilarity(0.0f),
            policy);

    assertEquals(List.of(1L), rowNums(results));
    assertTrue(results.get(0).getSimilarity() > 0.99f);
  }

  private static LongTermsAndValues sequence(long... terms) {
    return LongTermsAndValuesTestFactory.create(terms, new float[0], terms.length);
  }

  private static Comparator ngldComparator() {
    return ComparatorFactory.createComparator(
        "ngld",
        Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "levenshtein"),
        new ComplementComparatorNormalizer());
  }

  private static MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy(
      boolean mergeScoresFromAccumulatedConjunction) {
    ConjunctionScored conjunctionScored =
        ComparatorFactory.conjunctionScored(COMPARATOR, ComparatorType.JACCARD);
    return MergeSearch.PartialConjunctionPolicy.fromConfiguredComparator(conjunctionScored);
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
