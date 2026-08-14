/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.dense;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.MathUtils;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/** Pure Java exact dense-vector index backed by a row-major float matrix. */
public final class DenseMatrixIndex extends Index {
  private final ComparatorNormalizer comparatorNormalizer;
  private final DenseMatrixDotProductScorer dotProductScorer;
  private final int dimension;
  private final long[] rowNums;
  private final float[] rowMajorValues;
  private final double[] rowSquaredNorms;
  private final LongIntHashMap rowNumToMatrixRowIndex;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;

  public DenseMatrixIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    validateL2ComparatorType(namespaceConfig);
    this.comparatorNormalizer =
        ComparatorNormalizerFactory.createComparatorNormalizer(
            namespaceConfig.getComparatorNormalizerType(),
            namespaceConfig.getComparatorNormalizerParams());

    MatrixData matrixData = buildMatrixData();
    this.dimension = matrixData.dimension;
    this.rowNums = matrixData.rowNums;
    this.rowMajorValues = matrixData.rowMajorValues;
    this.rowSquaredNorms = matrixData.rowSquaredNorms;
    this.rowNumToMatrixRowIndex = matrixData.rowNumToMatrixRowIndex;
    this.dotProductScorer =
        DenseMatrixDotProductScorers.create(rowMajorValues, rowNums.length, dimension);
    /*
     * Dense matrix scoring cannot push arbitrary metadata filters into the bulk scorer. AUTO tries
     * selective pre-filtering first and otherwise falls back to post-filtering.
     */
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
  public List<RowNumAndSimilarity> getNearestNeighbors(
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
    double querySquaredNorm = computeSquaredNorm(queryValues);

    return metadataFilteredSearchExecutor.search(
        metadataFilter,
        maxResults,
        (resolvedMetadataFilter, resolvedMaxResults) ->
            searchAllMatrixRows(
                queryValues,
                querySquaredNorm,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults),
        (candidateRowNums, resolvedMetadataFilter, resolvedMaxResults) ->
            searchRowNums(
                queryValues,
                querySquaredNorm,
                candidateRowNums,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults));
  }

  private List<RowNumAndSimilarity> searchRowNums(
      float[] queryValues,
      double querySquaredNorm,
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
        queryValues, querySquaredNorm, matrixRowIndexes, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> searchAllMatrixRows(
      float[] queryValues,
      double querySquaredNorm,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    if (metadataFilter == null) {
      /*
       * No metadata filter is present, so every row can be scored with one bulk matrix-vector
       * multiply.
       */
      return searchAllMatrixRowsWithDotProductScorer(
          queryValues, querySquaredNorm, minSimilarity, maxResults);
    }
    // With in-filtering, skip non-matching rows before paying the per-row similarity cost.
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (int matrixRowIndex = 0; matrixRowIndex < rowNums.length; ++matrixRowIndex) {
      addMatchingRow(
          rows, queryValues, querySquaredNorm, matrixRowIndex, metadataFilter, minSimilarity);
    }
    return rows.toList();
  }

  private List<RowNumAndSimilarity> searchAllMatrixRowsWithDotProductScorer(
      float[] queryValues, double querySquaredNorm, float minSimilarity, int maxResults) {
    float[] dotProducts = new float[rowNums.length];
    dotProductScorer.score(queryValues, dotProducts);

    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (int matrixRowIndex = 0; matrixRowIndex < rowNums.length; ++matrixRowIndex) {
      long rowNum = rowNums[matrixRowIndex];
      if (isDeleted(rowNum)) {
        continue;
      }
      float similarity =
          computeL2SimilarityFromDotProduct(
              querySquaredNorm, matrixRowIndex, dotProducts[matrixRowIndex]);
      if (similarity >= minSimilarity) {
        rows.add(new RowNumAndSimilarity(rowNum, similarity));
      }
    }
    return rows.toList();
  }

  private List<RowNumAndSimilarity> searchMatrixRows(
      float[] queryValues,
      double querySquaredNorm,
      IntHashSet matrixRowIndexes,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (IntCursor matrixRowIndex : matrixRowIndexes) {
      addMatchingRow(
          rows, queryValues, querySquaredNorm, matrixRowIndex.value, metadataFilter, minSimilarity);
    }
    return rows.toList();
  }

  private void addMatchingRow(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      float[] queryValues,
      double querySquaredNorm,
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
    float similarity =
        computeL2SimilarityForMatrixRow(queryValues, querySquaredNorm, matrixRowIndex);
    if (similarity >= minSimilarity) {
      rows.add(new RowNumAndSimilarity(rowNum, similarity));
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  private float computeL2SimilarityForMatrixRow(
      float[] queryValues, double querySquaredNorm, int matrixRowIndex) {
    int offset = matrixRowIndex * dimension;
    double dotProduct = 0.0d;
    for (int i = 0; i < dimension; ++i) {
      dotProduct += queryValues[i] * rowMajorValues[offset + i];
    }
    return computeL2SimilarityFromDotProduct(querySquaredNorm, matrixRowIndex, dotProduct);
  }

  private float computeL2SimilarityFromDotProduct(
      double querySquaredNorm, int matrixRowIndex, double dotProduct) {
    /*
     * Compute ||query - row||^2 from precomputed row norms and the dot product. The clamp absorbs
     * small negative values from floating-point round-off.
     */
    double squaredDistance =
        Math.max(0.0d, querySquaredNorm + rowSquaredNorms[matrixRowIndex] - 2.0d * dotProduct);
    double distance = Math.sqrt(squaredDistance);
    return (float) comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(distance);
  }

  private float[] getDenseQueryValues(LongTermsAndValues record) {
    if (record == null) {
      throw new NullPointerException("record is null.");
    }
    if (record.termsLength() != 0) {
      throw new IllegalArgumentException(
          "DenseMatrixIndex expects query records with empty terms.");
    }
    if (record.valuesLength() != dimension) {
      throw new IllegalArgumentException(
          String.format(
              "DenseMatrixIndex query dimension mismatch. Expected %s values, got %s.",
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
    double[] matrixRowSquaredNorms = new double[numRows];
    LongIntHashMap matrixRowIndexByRowNum = new LongIntHashMap(numRows);

    int matrixRowIndex = 0;
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      long rowNum = entry.key;
      float[] values = entry.value.getValues();
      matrixRowNums[matrixRowIndex] = rowNum;
      matrixRowIndexByRowNum.put(rowNum, matrixRowIndex);
      System.arraycopy(
          values, 0, matrixValues, matrixRowIndex * inferredDimension, inferredDimension);
      matrixRowSquaredNorms[matrixRowIndex] = computeSquaredNorm(values);
      ++matrixRowIndex;
    }

    return new MatrixData(
        inferredDimension,
        matrixRowNums,
        matrixValues,
        matrixRowSquaredNorms,
        matrixRowIndexByRowNum);
  }

  private static int validateDenseRow(
      long rowNum, LongTermsAndValues termsAndValues, int expectedDimension) {
    if (termsAndValues.termsLength() != 0) {
      throw new IllegalArgumentException(
          String.format("DenseMatrixIndex row %s has non-empty terms.", rowNum));
    }
    int dimension = termsAndValues.valuesLength();
    if (dimension == 0) {
      throw new IllegalArgumentException(
          String.format("DenseMatrixIndex row %s has no values.", rowNum));
    }
    if (expectedDimension >= 0 && dimension != expectedDimension) {
      throw new IllegalArgumentException(
          String.format(
              "DenseMatrixIndex row %s dimension mismatch. Expected %s values, got %s.",
              rowNum, expectedDimension, dimension));
    }
    return dimension;
  }

  private static double computeSquaredNorm(float[] values) {
    MathUtils.StableSumAccumulator squaredNorm = new MathUtils.StableSumAccumulator();
    for (float value : values) {
      squaredNorm.add((double) value * value);
    }
    return squaredNorm.getSum();
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

  private static void validateL2ComparatorType(NamespaceConfig namespaceConfig) {
    if (!"l2".equals(namespaceConfig.getComparatorType())) {
      throw new IllegalArgumentException(
          String.format(
              "DenseMatrixIndex only supports the l2 comparator, got %s.",
              namespaceConfig.getComparatorType()));
    }
  }

  private static final class MatrixData {
    private final int dimension;
    private final long[] rowNums;
    private final float[] rowMajorValues;
    private final double[] rowSquaredNorms;
    private final LongIntHashMap rowNumToMatrixRowIndex;

    private MatrixData(
        int dimension,
        long[] rowNums,
        float[] rowMajorValues,
        double[] rowSquaredNorms,
        LongIntHashMap rowNumToMatrixRowIndex) {
      this.dimension = dimension;
      this.rowNums = rowNums;
      this.rowMajorValues = rowMajorValues;
      this.rowSquaredNorms = rowSquaredNorms;
      this.rowNumToMatrixRowIndex = rowNumToMatrixRowIndex;
    }
  }
}
