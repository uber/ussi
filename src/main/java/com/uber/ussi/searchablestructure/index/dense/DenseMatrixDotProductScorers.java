/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.dense;

import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Factory and validation helpers for dense matrix-vector dot-product scorers. */
final class DenseMatrixDotProductScorers {

  private DenseMatrixDotProductScorers() {}

  static DenseMatrixDotProductScorer create(float[] rowMajorValues, int numRows, int dimension) {
    validateMatrix(rowMajorValues, numRows, dimension);
    return create(
        rowMajorValues,
        numRows,
        dimension,
        OpenBlasDenseMatrixDotProductScorer.isAvailable(),
        () ->
            new OpenBlasDenseMatrixDotProductScorer(
                rowMajorValues,
                numRows,
                dimension,
                OpenBlasDenseMatrixDotProductScorer::isAvailable));
  }

  static DenseMatrixDotProductScorer create(
      float[] rowMajorValues,
      int numRows,
      int dimension,
      boolean openBlasAvailable,
      Supplier<DenseMatrixDotProductScorer> openBlasScorerSupplier) {
    validateMatrix(rowMajorValues, numRows, dimension);
    if (openBlasAvailable) {
      try {
        return openBlasScorerSupplier.get();
      } catch (LinkageError e) {
        return new JavaDenseMatrixDotProductScorer(rowMajorValues, numRows, dimension);
      } catch (RuntimeException e) {
        if (isCausedByLinkageError(e)) {
          return new JavaDenseMatrixDotProductScorer(rowMajorValues, numRows, dimension);
        }
        throw e;
      }
    }
    return new JavaDenseMatrixDotProductScorer(rowMajorValues, numRows, dimension);
  }

  static void validateMatrix(@Nullable float[] rowMajorValues, int numRows, int dimension) {
    if (numRows < 0) {
      throw new IllegalArgumentException("numRows must be >= 0.");
    }
    if (dimension < 0) {
      throw new IllegalArgumentException("dimension must be >= 0.");
    }
    long expectedLength = (long) numRows * dimension;
    if (expectedLength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Dense matrix dimensions exceed Integer.MAX_VALUE.");
    }
    if (rowMajorValues != null && rowMajorValues.length != (int) expectedLength) {
      throw new IllegalArgumentException(
          String.format(
              "rowMajorValues length mismatch. Expected %s, got %s.",
              expectedLength, rowMajorValues.length));
    }
  }

  static void validateScoreInputs(
      @Nullable float[] rowMajorValues,
      int numRows,
      int dimension,
      float[] queryValues,
      float[] dotProducts) {
    validateMatrix(rowMajorValues, numRows, dimension);
    if (queryValues.length != dimension) {
      throw new IllegalArgumentException(
          String.format(
              "queryValues length mismatch. Expected %s, got %s.", dimension, queryValues.length));
    }
    if (dotProducts.length != numRows) {
      throw new IllegalArgumentException(
          String.format(
              "dotProducts length mismatch. Expected %s, got %s.", numRows, dotProducts.length));
    }
  }

  private static boolean isCausedByLinkageError(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof LinkageError) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
