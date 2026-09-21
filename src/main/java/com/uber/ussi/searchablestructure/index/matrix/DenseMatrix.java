/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/**
 * A row-major dense matrix held in chunks, each a whole number of rows.
 *
 * <p>A namespace can hold more values than one Java array can, so the matrix is divided rather than
 * refused. Dividing on a row boundary keeps every row contiguous, which is what a bulk multiply
 * needs, and keeps the index of a value within its chunk inside {@code int} range.
 */
final class DenseMatrix {

  /**
   * The largest chunk to allocate. Slightly below {@link Integer#MAX_VALUE} because the maximum
   * array length is smaller than that by an amount the runtime does not specify.
   */
  static final int DEFAULT_MAX_CHUNK_VALUES = Integer.MAX_VALUE - 1024;

  private final float[][] chunks;
  private final int numRows;
  private final int dimension;
  private final int rowsPerChunk;

  private DenseMatrix(float[][] chunks, int numRows, int dimension, int rowsPerChunk) {
    this.chunks = chunks;
    this.numRows = numRows;
    this.dimension = dimension;
    this.rowsPerChunk = rowsPerChunk;
  }

  /** Allocates room for {@code numRows} rows, to be filled by {@link #setRow setRow()}. */
  static DenseMatrix allocate(int numRows, int dimension, int maxChunkValues) {
    if (numRows < 0) {
      throw new IllegalArgumentException("numRows must be >= 0.");
    }
    if (dimension < 0) {
      throw new IllegalArgumentException("dimension must be >= 0.");
    }
    if (maxChunkValues < 1) {
      throw new IllegalArgumentException("maxChunkValues must be >= 1.");
    }
    if (dimension > maxChunkValues) {
      throw new IllegalArgumentException(
          String.format(
              "A row of %s values does not fit a chunk of %s.", dimension, maxChunkValues));
    }
    if (numRows == 0 || dimension == 0) {
      return new DenseMatrix(new float[0][], numRows, dimension, Math.max(1, numRows));
    }

    int rowsPerChunk = maxChunkValues / dimension;
    int numChunks = (numRows + rowsPerChunk - 1) / rowsPerChunk;
    float[][] chunks = new float[numChunks][];
    for (int chunk = 0; chunk < numChunks; chunk++) {
      chunks[chunk] = new float[numRowsInChunk(numRows, rowsPerChunk, chunk) * dimension];
    }
    return new DenseMatrix(chunks, numRows, dimension, rowsPerChunk);
  }

  int numRows() {
    return numRows;
  }

  int dimension() {
    return dimension;
  }

  int numChunks() {
    return chunks.length;
  }

  float[] chunk(int chunk) {
    return chunks[chunk];
  }

  /** The matrix row the given chunk starts at. */
  int firstRowInChunk(int chunk) {
    return chunk * rowsPerChunk;
  }

  int numRowsInChunk(int chunk) {
    return numRowsInChunk(numRows, rowsPerChunk, chunk);
  }

  void setRow(int row, float[] values) {
    if (values.length != dimension) {
      throw new IllegalArgumentException(
          String.format("Row %s has %s values, expected %s.", row, values.length, dimension));
    }
    System.arraycopy(values, 0, chunks[row / rowsPerChunk], offsetInChunk(row), dimension);
  }

  float valueAt(int row, int column) {
    return chunks[row / rowsPerChunk][offsetInChunk(row) + column];
  }

  /**
   * Drops the chunk arrays, so they are eligible for collection once no scorer holds them. The
   * geometry stays, so a scorer that copied the values into its own buffers still walks the ranges.
   */
  void releaseValues() {
    for (int chunk = 0; chunk < chunks.length; chunk++) {
      chunks[chunk] = null;
    }
  }

  /** Whether the chunk arrays are still held, which a scorer that did not copy them needs. */
  boolean holdsValues() {
    return chunks.length == 0 || chunks[0] != null;
  }

  /** Where a row starts within its own chunk. Inside {@code int} range because a chunk is. */
  private int offsetInChunk(int row) {
    return (row % rowsPerChunk) * dimension;
  }

  private static int numRowsInChunk(int numRows, int rowsPerChunk, int chunk) {
    return Math.min(rowsPerChunk, numRows - chunk * rowsPerChunk);
  }
}
