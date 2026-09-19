/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.function.Supplier;

/** Chooses the dense scorer to use and checks the inputs both of them require. */
final class MatrixDotProductScorers {

  private MatrixDotProductScorers() {}

  static MatrixDotProductScorer create(DenseMatrix matrix) {
    return create(
        matrix, OpenBlas.isAvailable(), () -> OpenBlas.createScorer(matrix, OpenBlas::isAvailable));
  }

  static MatrixDotProductScorer create(
      DenseMatrix matrix,
      boolean nativeBlasAvailable,
      Supplier<MatrixDotProductScorer> nativeScorerSupplier) {
    if (nativeBlasAvailable) {
      try {
        return nativeScorerSupplier.get();
      } catch (LinkageError e) {
        return new JavaMatrixDotProductScorer(matrix);
      } catch (RuntimeException e) {
        if (isCausedByLinkageError(e)) {
          return new JavaMatrixDotProductScorer(matrix);
        }
        throw e;
      }
    }
    return new JavaMatrixDotProductScorer(matrix);
  }

  static void validateScoreInputs(
      DenseMatrix matrix, float[] queryValues, float[] dotProducts) {
    if (queryValues.length != matrix.dimension()) {
      throw new IllegalArgumentException(
          String.format(
              "queryValues length mismatch. Expected %s, got %s.",
              matrix.dimension(), queryValues.length));
    }
    if (dotProducts.length != matrix.numRows()) {
      throw new IllegalArgumentException(
          String.format(
              "dotProducts length mismatch. Expected %s, got %s.",
              matrix.numRows(), dotProducts.length));
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
