package com.uber.ussi.searchablestructure.index.inverted.generator;

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
import com.uber.ussi.searchablestructure.inverted.KeyAndPrefixFilteringData;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class FilteredSearchTest {
  private static final Comparator COMPARATOR = ComparatorFactory.createComparator(config());
  private static final Map<Long, Double> UNI_VALUES = Map.of(1L, 1.0, 2L, 2.0, 3L, 3.0, 4L, 4.0);

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
          FilteredSearch.getFirstMatchingUniValue(
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
          FilteredSearch.getLastMatchingUniValue(
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
    KeyAndPrefixFilteringData[] queryKeys = {
      new KeyAndPrefixFilteringData(10, 2, 1.0), new KeyAndPrefixFilteringData(20, 2, 1.0)
    };

    List<RowNumAndSimilarity> results =
        FilteredSearch.search(
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
    FilteredSearch.CandidateIterator iterator = candidateIterator(0.5);

    assertThrows(IllegalArgumentException.class, () -> iterator.setMinSimilarity(0.4));
  }

  @Test
  void candidateIteratorHasNothingLeftOnceExhausted() {
    FilteredSearch.CandidateIterator iterator = candidateIterator(0.0);

    assertFalse(iterator.hasNext());
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  /** A query key carries its list's length, so another length means the index changed. */
  @Test
  void candidateIteratorRejectsAnInvertedListOfUnexpectedLength() {
    FilteredSearch.CandidateIterator iterator =
        candidateIterator(0.0, new KeyAndPrefixFilteringData(10, 99, 1.0));

    assertThrows(IllegalStateException.class, iterator::hasNext);
  }

  /** Raising the threshold mid-traversal can put the spent prefix cost over the new budget. */
  @Test
  void candidateIteratorAbandonsTheCurrentKeyWhenTheTighterThresholdOutlawsItsPrefixCost() {
    // Every row shares the query's uni value, so only the prefix budget can end the traversal.
    FilteredSearch.Context context =
        stubContext(
            Map.of(10L, new long[] {1}, 20L, new long[] {5, 6}),
            Map.of(1L, 1.0, 5L, 1.0, 6L, 1.0),
            minSimilarity -> minSimilarity < 0.6 ? Double.POSITIVE_INFINITY : 0.5);
    FilteredSearch.CandidateIterator iterator =
        new FilteredSearch.CandidateIterator(
            COMPARATOR,
            context,
            new KeyAndPrefixFilteringData[] {
              new KeyAndPrefixFilteringData(10, 1, 1.0), new KeyAndPrefixFilteringData(20, 2, 1.0)
            },
            1.0,
            0.0);

    // Drains the first key, then one row of the second, whose prefix cost is the first's
    // uni-transformed value; row 6 is left pending.
    assertEquals(List.of(1L, 5L), List.of(iterator.next(), iterator.next()));

    iterator.setMinSimilarity(0.9);

    assertFalse(iterator.hasNext());
  }

  @Test
  void validateUniValueSearchRejectsInvalidComparatorUniValue() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FilteredSearch.getFirstMatchingUniValue(
                COMPARATOR, stubContext(), new long[] {1}, Constants.UNSET_UNI_VALUE, 0.5, 0, 1));
  }

  private static FilteredSearch.CandidateIterator candidateIterator(
      double minSimilarity, KeyAndPrefixFilteringData... queryKeys) {
    return new FilteredSearch.CandidateIterator(
        COMPARATOR, stubContext(), queryKeys, 1.0, minSimilarity);
  }

  private static FilteredSearch.Context stubContext() {
    return stubContext(Map.of(10L, new long[] {1, 2}, 20L, new long[] {1, 3}));
  }


  private static FilteredSearch.Context stubContext(Map<Long, long[]> rowNumsByKey) {
    return stubContext(rowNumsByKey, UNI_VALUES, minSimilarity -> Double.POSITIVE_INFINITY);
  }

  private static FilteredSearch.Context stubContext(
      Map<Long, long[]> rowNumsByKey,
      Map<Long, Double> uniValuesByRowNum,
      DoubleUnaryOperator minPrefixSum) {
    return new FilteredSearch.Context() {
      @Override
      public double getMinPrefixSum(double keysUniValue, double minSimilarity) {
        return minPrefixSum.applyAsDouble(minSimilarity);
      }

      @Override
      public long[] getRowNums(long key) {
        return rowNumsByKey.getOrDefault(key, new long[0]);
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
        .cacheType("scan")
        .indexType("inverted_term")
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
