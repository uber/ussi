package com.uber.ussi.searchablestructure.index.sparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    SparseFilteredSearch.CandidateIterator iterator =
        new SparseFilteredSearch.CandidateIterator(
            COMPARATOR, stubContext(), new SparseKeyAndPrefixFilteringData[0], 1.0, 0.5);

    assertThrows(IllegalArgumentException.class, () -> iterator.setMinSimilarity(0.4));
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

  private static SparseFilteredSearch.Context stubContext() {
    return stubContext(Map.of(10L, new long[] {1, 2}, 20L, new long[] {1, 3}));
  }

  private static SparseFilteredSearch.Context stubContext(Map<Long, long[]> rowNumsBySparseKey) {
    return new SparseFilteredSearch.Context() {
      @Override
      public double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
        return Double.POSITIVE_INFINITY;
      }

      @Override
      public long[] getRowNums(long sparseKey) {
        return rowNumsBySparseKey.getOrDefault(sparseKey, new long[0]);
      }

      @Override
      public double getUniValue(long rowNum) {
        Double uniValue = UNI_VALUES.get(rowNum);
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
        .indexType("inverted")
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
