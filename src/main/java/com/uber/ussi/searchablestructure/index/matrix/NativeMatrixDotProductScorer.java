/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.ArrayList;
import java.util.List;

/**
 * A dense matrix-vector dot-product scorer over a native library.
 *
 * <p>The matrix is copied into native buffers once, one per chunk and in the same order, so a
 * chunk is multiplied in place. A chunk is multiplied a slice of rows at a time rather than
 * whole, because the products of one slice are held for every query being multiplied at once and
 * a chunk holds as many rows as a Java array can index. Slicing bounds that buffer by the slice
 * rather than by the matrix, at no cost to the multiply, which still reads each row once.
 *
 * <p>One set of working buffers serves the whole scorer, since the caller serializes multiplies.
 */
final class NativeMatrixDotProductScorer<B> extends BatchedMatrixDotProductScorer {

  /**
   * Rows multiplied at once. Wide enough that a multiply is worth its call and that a row is read
   * once for every query in it, narrow enough that the products of a full combination stay a few
   * megabytes. The measurements that chose combining over dividing were taken at this width.
   */
  static final int MAX_NUM_ROWS_IN_A_SLICE = 8_192;

  private final NativeBlas<B> blas;
  private final List<B> chunks;
  private final B queries;
  private final B products;
  private final float[] readBuffer;

  NativeMatrixDotProductScorer(DenseMatrix matrix, NativeBlas<B> blas) {
    this(matrix, blas, Math.max(1, Runtime.getRuntime().availableProcessors()));
  }

  /**
   * @param maxNumQueriesInAMultiply the most queries to combine, which matches the searches that
   *     may run at once, since no more than that can ever be waiting.
   */
  NativeMatrixDotProductScorer(
      DenseMatrix matrix, NativeBlas<B> blas, int maxNumQueriesInAMultiply) {
    super(matrix, maxNumQueriesInAMultiply);
    this.blas = blas;
    this.chunks = new ArrayList<>(matrix.numChunks());
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      chunks.add(blas.allocate(matrix.chunk(chunk)));
    }
    int numProductsInASlice = maxNumQueriesInAMultiply * MAX_NUM_ROWS_IN_A_SLICE;
    this.queries = blas.allocate(maxNumQueriesInAMultiply * matrix.dimension());
    this.products = blas.allocate(numProductsInASlice);
    this.readBuffer = new float[numProductsInASlice];
  }

  @Override
  protected void multiplyOneQuery(float[] queryValues, float[] dotProducts) {
    int dimension = getMatrix().dimension();
    blas.write(queries, 0, queryValues, dimension);
    forEachSlice(
        (chunk, firstRowInSlice, numRowsInSlice, firstRow) -> {
          blas.multiply(
              numRowsInSlice,
              dimension,
              chunks.get(chunk),
              (long) firstRowInSlice * dimension,
              queries,
              products);
          blas.read(products, dotProducts, firstRow, numRowsInSlice);
        });
  }

  @Override
  protected void multiplyQueries(float[][] queryValues, float[][] dotProducts, int numQueries) {
    int dimension = getMatrix().dimension();
    for (int query = 0; query < numQueries; ++query) {
      blas.write(queries, (long) query * dimension, queryValues[query], dimension);
    }
    forEachSlice(
        (chunk, firstRowInSlice, numRowsInSlice, firstRow) -> {
          blas.multiplyQueries(
              numQueries,
              numRowsInSlice,
              dimension,
              chunks.get(chunk),
              (long) firstRowInSlice * dimension,
              queries,
              products);
          blas.read(products, readBuffer, 0, numQueries * numRowsInSlice);
          for (int query = 0; query < numQueries; ++query) {
            System.arraycopy(
                readBuffer, query * numRowsInSlice, dotProducts[query], firstRow, numRowsInSlice);
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

  /** Walks every slice of every chunk, in row order. */
  private void forEachSlice(SliceMultiply sliceMultiply) {
    DenseMatrix matrix = getMatrix();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      int numRowsInChunk = matrix.numRowsInChunk(chunk);
      for (int firstRowInSlice = 0;
          firstRowInSlice < numRowsInChunk;
          firstRowInSlice += MAX_NUM_ROWS_IN_A_SLICE) {
        int numRowsInSlice =
            Math.min(MAX_NUM_ROWS_IN_A_SLICE, numRowsInChunk - firstRowInSlice);
        sliceMultiply.run(
            chunk,
            firstRowInSlice,
            numRowsInSlice,
            matrix.firstRowInChunk(chunk) + firstRowInSlice);
      }
    }
  }

  /** What to do with one slice of one chunk. */
  private interface SliceMultiply {
    void run(int chunk, int firstRowInSlice, int numRowsInSlice, int firstRow);
  }
}
