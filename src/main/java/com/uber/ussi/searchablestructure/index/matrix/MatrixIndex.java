/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Pure Java exact dense-vector index backed by a row-major float matrix.
 *
 * <p>One matrix multiply scores every row at once, so the comparator never sees a candidate. It
 * supplies the arithmetic instead: each row's unilateral value, and the similarity a dot product
 * implies. Which comparators can do that is {@link Comparator#supportsDotProductScoring}, and
 * {@code IndexConfigValidator} rejects a namespace configured with one that cannot.
 */
public final class MatrixIndex extends Index {
  private final MatrixDotProductScorer dotProductScorer;
  private final int dimension;
  private final long[] rowNums;
  private final float[] rowMajorValues;
  private final double[] rowUniValues;
  private final LongIntHashMap rowNumToMatrixRowIndex;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;

  public MatrixIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    MatrixData matrixData = buildMatrixData();
    this.dimension = matrixData.dimension;
    this.rowNums = matrixData.rowNums;
    this.rowMajorValues = matrixData.rowMajorValues;
    this.rowUniValues = matrixData.rowUniValues;
    this.rowNumToMatrixRowIndex = matrixData.rowNumToMatrixRowIndex;
    this.dotProductScorer =
        MatrixDotProductScorers.create(rowMajorValues, rowNums.length, dimension);
    // Dense bulk scoring cannot push metadata filters down, so AUTO pre-filters or post-filters.
    this.metadataFilteredSearchExecutor =
        new MetadataFilteredSearchExecutor(
            metadataFilteringStrategy,
            MetadataFilteringStrategy.POST_FILTERING,
            /* autoAttemptPreFiltering */ true,
            MetadataFilteringStrategy.POST_FILTERING,
            this::getMatchingRowNumsIfUnderPreFilteringLimit,
            this::getPostFilteringMaxResults,
            this::matchesMetaFilter);
  }

  @Override
  public List<RowNumAndSimilarity> getNearestNeighborRowNums(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, /* minSimilarity */ 0.0f, numResults);
  }

  @Override
  public List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return search(record, metadataFilter, minSimilarity, namespaceConfig.getMaxNumSimilarities());
  }

  MetadataFilteringStrategy getResolvedMetadataFilteringStrategyForLastSearchForTests() {
    return metadataFilteredSearchExecutor.getResolvedMetadataFilteringStrategyForLastSearch();
  }

  int getDimensionForTests() {
    return dimension;
  }

  @Override
  public void close() {
    dotProductScorer.close();
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    if (maxResults == 0 || rowNums.length == 0) {
      return Collections.emptyList();
    }
    float[] queryValues = getDenseQueryValues(record);
    double queryUniValue = comparator.computeUniValue(queryValues);

    return metadataFilteredSearchExecutor.search(
        metadataFilter,
        maxResults,
        (resolvedMetadataFilter, resolvedMaxResults) ->
            searchAllMatrixRows(
                queryValues,
                queryUniValue,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults),
        (candidateRowNums, resolvedMetadataFilter, resolvedMaxResults) ->
            searchRowNums(
                queryValues,
                queryUniValue,
                candidateRowNums,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults));
  }

  private List<RowNumAndSimilarity> searchRowNums(
      float[] queryValues,
      double queryUniValue,
      LongHashSet candidateRowNums,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    IntHashSet matrixRowIndexes = new IntHashSet(candidateRowNums.size());
    for (LongCursor rowNum : candidateRowNums) {
      if (rowNumToMatrixRowIndex.containsKey(rowNum.value)) {
        matrixRowIndexes.add(rowNumToMatrixRowIndex.get(rowNum.value));
      }
    }
    return searchMatrixRows(
        queryValues, queryUniValue, matrixRowIndexes, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> searchAllMatrixRows(
      float[] queryValues,
      double queryUniValue,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    if (metadataFilter == null) {
      // Unfiltered, so every row is scored with one bulk matrix-vector multiply.
      return searchAllMatrixRowsWithDotProductScorer(
          queryValues, queryUniValue, minSimilarity, maxResults);
    }
    // With in-filtering, skip non-matching rows before paying the per-row similarity cost.
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (int matrixRowIndex = 0; matrixRowIndex < rowNums.length; ++matrixRowIndex) {
      addMatchingRow(
          rows, queryValues, queryUniValue, matrixRowIndex, metadataFilter, minSimilarity);
    }
    return rows.toList();
  }

  private List<RowNumAndSimilarity> searchAllMatrixRowsWithDotProductScorer(
      float[] queryValues, double queryUniValue, float minSimilarity, int maxResults) {
    float[] dotProducts = new float[rowNums.length];
    dotProductScorer.score(queryValues, dotProducts);

    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (int matrixRowIndex = 0; matrixRowIndex < rowNums.length; ++matrixRowIndex) {
      long rowNum = rowNums[matrixRowIndex];
      if (isDeleted(rowNum)) {
        continue;
      }
      float similarity =
          computeSimilarityFromDotProduct(
              queryUniValue, matrixRowIndex, dotProducts[matrixRowIndex]);
      if (similarity >= minSimilarity) {
        rows.add(new RowNumAndSimilarity(rowNum, similarity));
      }
    }
    return rows.toList();
  }

  private List<RowNumAndSimilarity> searchMatrixRows(
      float[] queryValues,
      double queryUniValue,
      IntHashSet matrixRowIndexes,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (IntCursor matrixRowIndex : matrixRowIndexes) {
      addMatchingRow(
          rows, queryValues, queryUniValue, matrixRowIndex.value, metadataFilter, minSimilarity);
    }
    return rows.toList();
  }

  private void addMatchingRow(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      float[] queryValues,
      double queryUniValue,
      int matrixRowIndex,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity) {
    long rowNum = rowNums[matrixRowIndex];
    if (isDeleted(rowNum)) {
      return;
    }
    if (metadataFilter != null && !matchesMetaFilter(rowNum, metadataFilter)) {
      return;
    }
    float similarity = computeSimilarityForMatrixRow(queryValues, queryUniValue, matrixRowIndex);
    if (similarity >= minSimilarity) {
      rows.add(new RowNumAndSimilarity(rowNum, similarity));
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  /** Scores one row without the bulk multiply, for a search that reaches only some of them. */
  private float computeSimilarityForMatrixRow(
      float[] queryValues, double queryUniValue, int matrixRowIndex) {
    int offset = matrixRowIndex * dimension;
    double dotProduct = 0.0d;
    for (int i = 0; i < dimension; ++i) {
      dotProduct += (double) queryValues[i] * rowMajorValues[offset + i];
    }
    return computeSimilarityFromDotProduct(queryUniValue, matrixRowIndex, dotProduct);
  }

  private float computeSimilarityFromDotProduct(
      double queryUniValue, int matrixRowIndex, double dotProduct) {
    return (float)
        comparator.similarityFromDotProduct(
            dotProduct, queryUniValue, rowUniValues[matrixRowIndex]);
  }

  private float[] getDenseQueryValues(LongTermsAndValues record) {
    if (record == null) {
      throw new NullPointerException("record is null.");
    }
    if (record.termsLength() != 0) {
      throw new IllegalArgumentException("MatrixIndex expects query records with empty terms.");
    }
    if (record.valuesLength() != dimension) {
      throw new IllegalArgumentException(
          String.format(
              "MatrixIndex query dimension mismatch. Expected %s values, got %s.",
              dimension, record.valuesLength()));
    }
    float[] queryValues = new float[dimension];
    for (int i = 0; i < dimension; ++i) {
      queryValues[i] = record.getValue(i);
    }
    return queryValues;
  }

  private MatrixData buildMatrixData() {
    int numRows = rowNumToTermsAndValuesMap.size();
    if (numRows == 0) {
      return new MatrixData(
          /* dimension */ 0, new long[0], new float[0], new double[0], new LongIntHashMap());
    }

    int inferredDimension = -1;
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      inferredDimension = validateDenseRow(entry.key, entry.value, inferredDimension);
    }
    long numCells = validateMatrixCellCount(numRows, inferredDimension);

    long[] matrixRowNums = new long[numRows];
    float[] matrixValues = new float[(int) numCells];
    double[] matrixRowUniValues = new double[numRows];
    LongIntHashMap matrixRowIndexByRowNum = new LongIntHashMap(numRows);

    int matrixRowIndex = 0;
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      long rowNum = entry.key;
      float[] values = entry.value.getValues();
      matrixRowNums[matrixRowIndex] = rowNum;
      matrixRowIndexByRowNum.put(rowNum, matrixRowIndex);
      System.arraycopy(
          values, 0, matrixValues, matrixRowIndex * inferredDimension, inferredDimension);
      matrixRowUniValues[matrixRowIndex] = comparator.computeUniValue(values);
      ++matrixRowIndex;
    }

    return new MatrixData(
        inferredDimension,
        matrixRowNums,
        matrixValues,
        matrixRowUniValues,
        matrixRowIndexByRowNum);
  }

  private static int validateDenseRow(
      long rowNum, LongTermsAndValues termsAndValues, int expectedDimension) {
    if (termsAndValues.termsLength() != 0) {
      throw new IllegalArgumentException(
          String.format("MatrixIndex row %s has non-empty terms.", rowNum));
    }
    int dimension = termsAndValues.valuesLength();
    if (dimension == 0) {
      throw new IllegalArgumentException(
          String.format("MatrixIndex row %s has no values.", rowNum));
    }
    if (expectedDimension >= 0 && dimension != expectedDimension) {
      throw new IllegalArgumentException(
          String.format(
              "MatrixIndex row %s dimension mismatch. Expected %s values, got %s.",
              rowNum, expectedDimension, dimension));
    }
    return dimension;
  }

  static long validateMatrixCellCountForTests(int numRows, int dimension) {
    return validateMatrixCellCount(numRows, dimension);
  }

  private static long validateMatrixCellCount(int numRows, int dimension) {
    long numCells = (long) numRows * dimension;
    if (numCells > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          String.format(
              "Dense matrix is too large. Rows (%s) * dimensions (%s) exceeds Integer.MAX_VALUE.",
              numRows, dimension));
    }
    return numCells;
  }

  private static final class MatrixData {
    private final int dimension;
    private final long[] rowNums;
    private final float[] rowMajorValues;
    private final double[] rowUniValues;
    private final LongIntHashMap rowNumToMatrixRowIndex;

    private MatrixData(
        int dimension,
        long[] rowNums,
        float[] rowMajorValues,
        double[] rowUniValues,
        LongIntHashMap rowNumToMatrixRowIndex) {
      this.dimension = dimension;
      this.rowNums = rowNums;
      this.rowMajorValues = rowMajorValues;
      this.rowUniValues = rowUniValues;
      this.rowNumToMatrixRowIndex = rowNumToMatrixRowIndex;
    }
  }
}
