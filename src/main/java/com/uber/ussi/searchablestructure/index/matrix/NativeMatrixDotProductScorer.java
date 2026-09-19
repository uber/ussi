/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * A dense matrix-vector dot-product scorer over a native library.
 *
 * <p>The matrix is copied into native buffers once, one per chunk and in the same order, so a
 * chunk is multiplied in place. A chunk is multiplied a range of rows at a time rather than
 * whole, because the products of one range are held for every query being multiplied at once and
 * a chunk holds as many rows as a Java array can index. Bounding the range bounds that buffer by
 * the range rather than by the matrix, at no cost to the multiply, which still reads each row
 * once.
 *
 * <p>One set of working buffers serves the whole scorer, since the caller serializes multiplies.
 */
final class NativeMatrixDotProductScorer<B> extends BatchedMatrixDotProductScorer<float[]> {

  /**
   * Rows multiplied at once. Wide enough that a multiply is worth its call and that a row is read
   * once for every query in it, narrow enough that the products of a full batch stay a few
   * megabytes. The measurements that chose batching over dividing the threads used this
   * number of rows.
   */
  static final int MAX_NUM_ROWS_IN_A_RANGE = 8_192;

  private final NativeBlas<B> blas;
  /**
   * A dot product per row is as long as the matrix has rows, so one is reused rather than
   * allocated for every query. Bounded by the queries one multiply may carry, since no more are
   * ever held at once.
   */
  private final ArrayBlockingQueue<float[]> spareDotProducts;
  private final List<B> chunks;
  private final B queries;
  private final B products;
  private final float[] readBuffer;

  NativeMatrixDotProductScorer(DenseMatrix matrix, MatrixRows rows, NativeBlas<B> blas) {
    this(matrix, rows, blas, Math.max(1, Runtime.getRuntime().availableProcessors()));
  }

  /**
   * @param maxNumQueriesInABatch the most queries to batch, which matches the searches that
   *     may run at once, since no more than that can ever be waiting.
   */
  NativeMatrixDotProductScorer(
      DenseMatrix matrix, MatrixRows rows, NativeBlas<B> blas, int maxNumQueriesInABatch) {
    super(matrix, rows, maxNumQueriesInABatch);
    this.blas = blas;
    this.chunks = new ArrayList<>(matrix.numChunks());
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      chunks.add(blas.allocate(matrix.chunk(chunk)));
    }
    int numProductsInARange = maxNumQueriesInABatch * MAX_NUM_ROWS_IN_A_RANGE;
    this.queries = blas.allocate(maxNumQueriesInABatch * matrix.dimension());
    this.products = blas.allocate(numProductsInARange);
    this.readBuffer = new float[numProductsInARange];
    this.spareDotProducts = new ArrayBlockingQueue<>(maxNumQueriesInABatch);
  }

  @Override
  protected float[] newMultiplyResult() {
    float[] reused = spareDotProducts.poll();
    return reused != null ? reused : new float[getMatrix().numRows()];
  }

  @Override
  protected void recycleMultiplyResult(float[] result) {
    spareDotProducts.offer(result);
  }

  @Override
  protected float[][] newMultiplyResults(int numResults) {
    return new float[numResults][];
  }

  @Override
  protected void addRows(
      float[] result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    DotProductRows.addRows(result, getRows(), selection, rows);
  }

  @Override
  protected void multiplyOneQuery(
      float[] queryValues, RowSelection selection, float[] dotProducts) {
    int dimension = getMatrix().dimension();
    blas.write(queries, 0, queryValues, dimension);
    forEachRange(
        (chunk, firstRowInRange, numRowsInRange, firstRow) -> {
          blas.multiply(
              numRowsInRange,
              dimension,
              chunks.get(chunk),
              (long) firstRowInRange * dimension,
              queries,
              products);
          blas.read(products, dotProducts, firstRow, numRowsInRange);
        });
  }

  @Override
  protected void multiplyQueries(
      float[][] queryValues, RowSelection[] selections, float[][] dotProducts,
      int numQueries) {
    int dimension = getMatrix().dimension();
    for (int query = 0; query < numQueries; ++query) {
      blas.write(queries, (long) query * dimension, queryValues[query], dimension);
    }
    forEachRange(
        (chunk, firstRowInRange, numRowsInRange, firstRow) -> {
          blas.multiplyQueries(
              numQueries,
              numRowsInRange,
              dimension,
              chunks.get(chunk),
              (long) firstRowInRange * dimension,
              queries,
              products);
          blas.read(products, readBuffer, 0, numQueries * numRowsInRange);
          for (int query = 0; query < numQueries; ++query) {
            System.arraycopy(
                readBuffer,
                query * numRowsInRange,
                dotProducts[query],
                firstRow,
                numRowsInRange);
          }
        });
  }

  @Override
  protected void releaseResources() {
    for (B chunk : chunks) {
      blas.free(chunk);
    }
    blas.free(queries);
    blas.free(products);
  }

  /** Walks every range of every chunk, in row order. */
  private void forEachRange(RangeMultiply rangeMultiply) {
    DenseMatrix matrix = getMatrix();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      int numRowsInChunk = matrix.numRowsInChunk(chunk);
      for (int firstRowInRange = 0;
          firstRowInRange < numRowsInChunk;
          firstRowInRange += MAX_NUM_ROWS_IN_A_RANGE) {
        int numRowsInRange =
            Math.min(MAX_NUM_ROWS_IN_A_RANGE, numRowsInChunk - firstRowInRange);
        rangeMultiply.run(
            chunk,
            firstRowInRange,
            numRowsInRange,
            matrix.firstRowInChunk(chunk) + firstRowInRange);
      }
    }
  }

  /** What to do with one range of one chunk. */
  private interface RangeMultiply {
    void run(int chunk, int firstRowInRange, int numRowsInRange, int firstRow);
  }
}
