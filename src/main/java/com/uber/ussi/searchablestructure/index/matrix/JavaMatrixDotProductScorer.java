/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.ParallelismBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure Java dense matrix-vector dot-product scorer.
 *
 * <p>The multiply is divided between the threads one search may use, each thread taking a range of
 * the matrix's rows. The ranges do not overlap, so the threads write disjoint parts of the dot
 * products and need nothing to coordinate them beyond waiting for all of them to finish.
 *
 * <p>The OpenBLAS scorer is threaded by OpenBLAS itself, from the same budget. This scorer is what
 * runs where that one is unavailable, and it took no threads at all before.
 */
final class JavaMatrixDotProductScorer implements MatrixDotProductScorer {

  /**
   * Multiply-adds a score must do before its rows are worth dividing between threads. Below it the
   * hand-off costs more than the multiply it shortens.
   *
   * <p>Carried over from the row-visit minimum a split scan uses, and unmeasured for this multiply.
   */
  private static final long MIN_MULTIPLY_ADDS_TO_SPLIT = 4_096;

  /**
   * Threads the multiply hands off to. Sized to the cores, which is what the searches in flight
   * demand together: searches are admitted up to the core count, and each divides the cores among
   * its own rows.
   */
  private static final ExecutorService MULTIPLIERS = createMultipliers();

  private final DenseMatrix matrix;

  JavaMatrixDotProductScorer(DenseMatrix matrix) {
    this.matrix = matrix;
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    int numRows = matrix.numRows();
    int numParts = partCount(numRows, matrix.dimension(), ParallelismBudget.shared().budget());
    if (numParts == 1) {
      scoreRows(0, numRows, queryValues, dotProducts);
      return;
    }
    int rowsPerPart = (numRows + numParts - 1) / numParts;
    List<Future<?>> handedOffParts = new ArrayList<>(numParts - 1);
    for (int part = 1; part < numParts; part++) {
      int firstRow = part * rowsPerPart;
      int afterLastRow = Math.min(numRows, firstRow + rowsPerPart);
      if (firstRow >= afterLastRow) {
        break;
      }
      handedOffParts.add(
          MULTIPLIERS.submit(() -> scoreRows(firstRow, afterLastRow, queryValues, dotProducts)));
    }
    // The calling thread takes a range rather than waiting on all of them, which both uses the
    // thread already here and keeps the multiply progressing when every pool thread is busy.
    scoreRows(0, Math.min(numRows, rowsPerPart), queryValues, dotProducts);
    awaitParts(handedOffParts);
  }

  /**
   * Parts to divide {@code numRows} into: every thread this search may use once the multiply is
   * worth dividing, and one before that. No part is without a row in it.
   */
  static int partCount(int numRows, int dimension, int parallelism) {
    if ((long) numRows * dimension < MIN_MULTIPLY_ADDS_TO_SPLIT) {
      return 1;
    }
    return Math.max(1, Math.min(parallelism, numRows));
  }

  /** Scores the rows of one range, which lie in one chunk of the matrix or across several. */
  private void scoreRows(
      int firstRow, int afterLastRow, float[] queryValues, float[] dotProducts) {
    int dimension = matrix.dimension();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      int firstRowOfChunk = matrix.firstRowInChunk(chunk);
      int firstRowToScore = Math.max(firstRow, firstRowOfChunk);
      int afterLastRowToScore =
          Math.min(afterLastRow, firstRowOfChunk + matrix.numRowsInChunk(chunk));
      if (firstRowToScore >= afterLastRowToScore) {
        continue;
      }
      float[] values = matrix.chunk(chunk);
      for (int row = firstRowToScore; row < afterLastRowToScore; ++row) {
        int offset = (row - firstRowOfChunk) * dimension;
        float dotProduct = 0.0f;
        for (int col = 0; col < dimension; ++col) {
          dotProduct += values[offset + col] * queryValues[col];
        }
        dotProducts[row] = dotProduct;
      }
    }
  }

  /**
   * Waits for every handed-off range, including once one has failed. A range left running would
   * still be writing the dot products its caller had already begun to read.
   */
  private static void awaitParts(List<Future<?>> handedOffParts) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<?> part : handedOffParts) {
      try {
        part.get();
      } catch (InterruptedException e) {
        interrupted = true;
        failure = failure != null ? failure : new IllegalStateException(MULTIPLY_FAILED, e);
      } catch (ExecutionException e) {
        // An undivided multiply would have thrown this from the caller's thread, so it is rethrown.
        RuntimeException thrown =
            e.getCause() instanceof RuntimeException runtimeCause
                ? runtimeCause
                : new IllegalStateException(MULTIPLY_FAILED, e.getCause());
        failure = failure != null ? failure : thrown;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static final String MULTIPLY_FAILED = "Failed to multiply part of the matrix.";

  private static ExecutorService createMultipliers() {
    AtomicInteger threadNumber = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        runnable -> {
          Thread thread = new Thread(runnable, "ussi-matrix-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }
}
