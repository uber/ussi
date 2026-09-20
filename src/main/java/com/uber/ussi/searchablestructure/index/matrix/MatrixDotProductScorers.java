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
        public MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows) {
          return new JavaMatrixDotProductScorer(matrix, rows);
        }
      };

  /**
   * Holds the matrix in a GPU's memory and scores there. <b>Never run on a GPU</b>, so it is
   * listed after a scorer that is always available and is therefore never built. Moving this
   * entry ahead of the Java scorer selects it, which should follow verifying its results on a
   * GPU rather than precede it.
   */
  private static final Provider CUDA =
      new Provider() {
        @Override
        public boolean isAvailable() {
          return CudaMatrixDotProductScorer.isAvailable();
        }

        @Override
        public MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows) {
          return new CudaMatrixDotProductScorer(
              matrix,
              rows,
              Math.max(1, Runtime.getRuntime().availableProcessors()),
              // Every query asking for more rows than this is refused, so it bounds what a
              // namespace may ask for rather than only what the device holds.
              /* maxResults */ 1_024);
        }
      };

  private static final Provider OPEN_BLAS =
      new Provider() {
        @Override
        public boolean isAvailable() {
          return OpenBlas.isAvailable();
        }

        @Override
        public MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows) {
          return new NativeMatrixDotProductScorer<>(matrix, rows, OpenBlas.shared());
        }
      };

  /**
   * Ordered by how fast a scorer is where it can be built, so the first one this machine can
   * build is the fastest it can run.
   *
   * <p>The Java scorer needs no native code and is therefore always available, which is what
   * makes the list terminate. Nothing after it is ever reached, which is where the CUDA scorer
   * sits: it is faster than both where a GPU is present, and it stays unreachable until its
   * results have been verified on one.
   */
  private static final List<Provider> PREFERENCE_ORDER = List.of(OPEN_BLAS, JAVA, CUDA);

  private MatrixDotProductScorers() {}

  static MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows) {
    return create(matrix, rows, PREFERENCE_ORDER);
  }

  static MatrixDotProductScorer create(
      DenseMatrix matrix, MatrixRows rows, List<Provider> preferenceOrder) {
    for (Provider provider : preferenceOrder) {
      try {
        if (!provider.isAvailable()) {
          continue;
        }
        return provider.create(matrix, rows);
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

    MatrixDotProductScorer create(DenseMatrix matrix, MatrixRows rows);
  }
}
