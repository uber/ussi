package com.uber.ussi.searchablestructure.index.sparse.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.sparse.SparseKeyAndPrefixFilteringData;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class SparseFilteredSearchTest {
  private static final Comparator COMPARATOR = ComparatorFactory.createComparator(config());
  private static final Map<Long, Double> UNI_VALUES =
      Map.of(1L, 1.0, 2L, 2.0, 3L, 3.0, 4L, 4.0);

  private static final UniBoundCase[] FIRST_MATCH_CASES = {
    new UniBoundCase("whole-range", new long[] {1, 2, 3, 4}, 2.0, 0.0, 0, 4, 0),
    new UniBoundCase("empty-tail", new long[] {1, 2, 3, 4}, 4.0, 0.9, 0, 4, 3),
  };

  private static final UniBoundCase[] LAST_MATCH_CASES = {
    new UniBoundCase("whole-range", new long[] {1, 2, 3, 4}, 2.0, 0.0, 0, 4, 4),
    new UniBoundCase("prefix-only", new long[] {1, 2, 3, 4}, 2.0, 0.9, 0, 4, 2),
  };

  @Test
  void getFirstMatchingUniValueCases() {
    for (UniBoundCase testCase : FIRST_MATCH_CASES) {
      assertEquals(
          testCase.expectedIndex,
          SparseFilteredSearch.getFirstMatchingUniValue(
              COMPARATOR,
              stubContext(),
              testCase.rowNums,
              testCase.comparatorUniValue,
              testCase.minSimilarity,
              testCase.fromIndex,
              testCase.toIndex),
          testCase.name);
    }
  }

  @Test
  void getLastMatchingUniValueCases() {
    for (UniBoundCase testCase : LAST_MATCH_CASES) {
      assertEquals(
          testCase.expectedIndex,
          SparseFilteredSearch.getLastMatchingUniValue(
              COMPARATOR,
              stubContext(),
              testCase.rowNums,
              testCase.comparatorUniValue,
              testCase.minSimilarity,
              testCase.fromIndex,
              testCase.toIndex),
          testCase.name);
    }
  }

  @Test
  void searchScoresCandidatesFromTheVerificationLookup() {
    LongTermsAndValues query = jaccard(new long[] {10, 20}, 1, 1);
    LongTermsAndValues row1 = jaccard(new long[] {10, 20}, 1, 1);
    LongTermsAndValues row2 = jaccard(new long[] {10}, 1);
    Map<Long, LongTermsAndValues> rows = Map.of(1L, row1, 2L, row2);
    SparseKeyAndPrefixFilteringData[] queryKeys = {
      new SparseKeyAndPrefixFilteringData(10, 2, 1.0),
      new SparseKeyAndPrefixFilteringData(20, 2, 1.0)
    };

    List<RowNumAndSimilarity> results =
        SparseFilteredSearch.search(
            COMPARATOR,
            query,
            query,
            null,
            0.0f,
            5,
            queryKeys,
            stubContext(),
            (rowNum, metadataFilter) -> true,
            rows::get);

    assertEquals(2, results.size());
    assertTrue(results.stream().anyMatch(result -> result.getRowNum() == 1));
    assertTrue(results.stream().anyMatch(result -> result.getRowNum() == 2));
  }

  @Test
  void candidateIteratorRejectsLoweringMinSimilarity() {
    SparseFilteredSearch.CandidateIterator iterator = candidateIterator(0.5);

    assertThrows(IllegalArgumentException.class, () -> iterator.setMinSimilarity(0.4));
  }

  @Test
  void candidateIteratorHasNothingLeftOnceExhausted() {
    SparseFilteredSearch.CandidateIterator iterator = candidateIterator(0.0);

    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  /**
   * A query key carries the length of the inverted list it was built against, so a list of another
   * length means the index changed underneath the query rather than that the key is absent.
   */
  @Test
  void candidateIteratorRejectsAnInvertedListOfUnexpectedLength() {
    SparseFilteredSearch.CandidateIterator iterator =
        candidateIterator(0.0, new SparseKeyAndPrefixFilteringData(10, 99, 1.0));

    assertThrows(IllegalStateException.class, iterator::hasNext);
  }

  /**
   * Raising the threshold mid-traversal can put the prefix cost already spent over the new budget,
   * which abandons the key being walked rather than finishing rows that can no longer qualify.
   */
  @Test
  void candidateIteratorAbandonsTheCurrentKeyWhenTheTighterThresholdOutlawsItsPrefixCost() {
    // Every row here shares the query's uni value, so length filtering alone would keep them all
    // and only the prefix budget can end the traversal early.
    SparseFilteredSearch.Context context =
        stubContext(
            Map.of(10L, new long[] {1}, 20L, new long[] {5, 6}),
            Map.of(1L, 1.0, 5L, 1.0, 6L, 1.0),
            minSimilarity -> minSimilarity < 0.6 ? Double.POSITIVE_INFINITY : 0.5);
    SparseFilteredSearch.CandidateIterator iterator =
        new SparseFilteredSearch.CandidateIterator(
            COMPARATOR,
            context,
            new SparseKeyAndPrefixFilteringData[] {
              new SparseKeyAndPrefixFilteringData(10, 1, 1.0),
              new SparseKeyAndPrefixFilteringData(20, 2, 1.0)
            },
            1.0,
            0.0);

    // Drains the first key, then takes one row of the second, whose prefix cost is the first's
    // uni-transformed value. Row 6 is left pending.
    assertEquals(List.of(1L, 5L), List.of(iterator.next(), iterator.next()));

    iterator.setMinSimilarity(0.9);

    assertFalse(iterator.hasNext());
  }

  @Test
  void validateUniValueSearchRejectsInvalidComparatorUniValue() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SparseFilteredSearch.getFirstMatchingUniValue(
                COMPARATOR,
                stubContext(),
                new long[] {1},
                Constants.UNSET_UNI_VALUE,
                0.5,
                0,
                1));
  }

  private static SparseFilteredSearch.CandidateIterator candidateIterator(
      double minSimilarity, SparseKeyAndPrefixFilteringData... queryKeys) {
    return new SparseFilteredSearch.CandidateIterator(
        COMPARATOR, stubContext(), queryKeys, 1.0, minSimilarity);
  }

  private static SparseFilteredSearch.Context stubContext() {
    return stubContext(Map.of(10L, new long[] {1, 2}, 20L, new long[] {1, 3}));
  }


  private static SparseFilteredSearch.Context stubContext(Map<Long, long[]> rowNumsBySparseKey) {
    return stubContext(rowNumsBySparseKey, UNI_VALUES, minSimilarity -> Double.POSITIVE_INFINITY);
  }

  private static SparseFilteredSearch.Context stubContext(
      Map<Long, long[]> rowNumsBySparseKey,
      Map<Long, Double> uniValuesByRowNum,
      DoubleUnaryOperator minPrefixSum) {
    return new SparseFilteredSearch.Context() {
      @Override
      public double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
        return minPrefixSum.applyAsDouble(minSimilarity);
      }

      @Override
      public long[] getRowNums(long sparseKey) {
        return rowNumsBySparseKey.getOrDefault(sparseKey, new long[0]);
      }

      @Override
      public double getUniValue(long rowNum) {
        Double uniValue = uniValuesByRowNum.get(rowNum);
        if (uniValue == null) {
          throw new IllegalStateException("missing uni value for row " + rowNum);
        }
        return uniValue;
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
        .cacheType("generic")
        .indexType("term")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  /** One search over {@code rowNums[fromIndex, toIndex)} and the bound index it should return. */
  private record UniBoundCase(
      String name,
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int fromIndex,
      int toIndex,
      int expectedIndex) {}
}
