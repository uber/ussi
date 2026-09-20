/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.List;

/**
 * Selects which dense scorer to build, and checks the query every one of them requires.
 *
 * <p>The scorers are tried in the order of {@link #PREFERENCE_ORDER}, and the first whose
 * implementation is available on this machine is built. A scorer that turns out to be
 * unavailable only when it is built, which is how a missing native library presents itself, is
 * skipped as though it had never claimed to be available, so the order continues past it.
 *
 * <p>Adding a scorer is adding a {@link Provider} to that list at the position it deserves. The
 * last entry needs no native code and is always available, so the list always ends somewhere.
 */
final class MatrixDotProductScorers {

  private static final Provider JAVA =
      new Provider() {
        @Override
        public boolean isAvailable() {
          return true;
        }

        @Override
        public MatrixDotProductScorer create(
            DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities) {
          return new JavaMatrixDotProductScorer(matrix, rows);
        }
      };

  /**
   * Holds the matrix in a GPU's memory and scores there, which is the fastest of these where a
   * GPU is present. Available only where the CUDA bindings are on the runtime classpath, which
   * is a deliberate addition, since this library depends on them at compile time alone.
   */
  private static final Provider CUDA =
      new Provider() {
        @Override
        public boolean isAvailable() {
          return CudaMatrixDotProductScorer.isAvailable();
        }

        @Override
        public MatrixDotProductScorer create(
            DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities) {
          return new CudaMatrixDotProductScorer(
              matrix,
              rows,
              Math.max(1, Runtime.getRuntime().availableProcessors()),
              maxNumSimilarities);
        }
      };

  private static final Provider OPEN_BLAS =
      new Provider() {
        @Override
        public boolean isAvailable() {
          return OpenBlas.isAvailable();
        }

        @Override
        public MatrixDotProductScorer create(
            DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities) {
          return new NativeMatrixDotProductScorer<>(matrix, rows, OpenBlas.shared());
        }
      };

  /**
   * Ordered by how fast a scorer is where it can be built, so the first one this machine can
   * build is the fastest it can run. The Java scorer needs no native code and is therefore
   * always available, which is what makes the list terminate.
   *
   * <p>The CUDA scorer leads it because a GPU outruns a CPU at this, and reaching it takes
   * both a GPU and the bindings, which this library depends on at compile time alone. A
   * deployment adding them is what selects it, and no result it produces has been verified on
   * a GPU.
   */
  private static final List<Provider> PREFERENCE_ORDER = List.of(CUDA, OPEN_BLAS, JAVA);

  private MatrixDotProductScorers() {}

  static MatrixDotProductScorer create(
      DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities) {
    return create(matrix, rows, maxNumSimilarities, PREFERENCE_ORDER);
  }

  static MatrixDotProductScorer create(
      DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities,
      List<Provider> preferenceOrder) {
    for (Provider provider : preferenceOrder) {
      try {
        if (!provider.isAvailable()) {
          continue;
        }
        return provider.create(matrix, rows, maxNumSimilarities);
      } catch (LinkageError e) {
        continue;
      } catch (RuntimeException e) {
        if (isCausedByLinkageError(e)) {
          continue;
        }
        throw e;
      }
    }
    throw new IllegalStateException("No dense scorer is available on this machine.");
  }

  static void validateQueryLength(DenseMatrix matrix, float[] queryValues) {
    if (queryValues.length != matrix.dimension()) {
      throw new IllegalArgumentException(
          String.format(
              "queryValues length mismatch. Expected %s, got %s.",
              matrix.dimension(), queryValues.length));
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

  /** A dense scorer that may or may not be usable on this machine. */
  interface Provider {
    /** Whether this machine can run it, which building it may still disprove. */
    boolean isAvailable();

    /**
     * Builds the scorer. The bound is the most rows any query against this namespace may ask
     * for, which a scorer that fixes how many it keeps is built to keep.
     */
    MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities);
  }
}
