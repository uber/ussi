/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/**
 * A native library that multiplies a matrix by one or several vectors, and the buffers it
 * multiplies out of.
 *
 * <p>Supporting a further library requires implementing this interface and nothing else. A dense
 * scorer allocates the buffers, reuses them across scores, traverses the matrix a range at a time
 * and batches the queries that are waiting, none of which depends on the library in use. An
 * implementation supplies the buffer operations and the two multiplies.
 *
 * <p>{@code B} is the implementation's own buffer handle, which a caller of this interface only
 * passes back, and every offset is counted in values rather than in bytes or addresses. An
 * implementation is therefore free to allocate its buffers wherever its library requires.
 *
 * <p>Calls are serialized by the caller, so an implementation may use every thread it is configured
 * for on one multiply, and need not be safe against concurrent multiplies of its own.
 *
 * <p>An implementation is instantiated once per process rather than once per matrix, because any
 * thread count it maintains is process-global.
 */
interface NativeBlas<B> {

  /** A buffer holding a copy of {@code values}, for the library to read repeatedly. */
  B allocate(float[] values);

  /** An empty buffer of {@code numValues}, for a caller to write and the library to read. */
  B allocate(int numValues);

  /** Releases a buffer this interface allocated. A buffer is released once. */
  void free(B buffer);

  /** Copies {@code numValues} of {@code values} into {@code buffer} at {@code bufferOffset}. */
  void write(B buffer, long bufferOffset, float[] values, int numValues);

  /**
   * Copies {@code numValues} out of {@code buffer} into {@code values}, starting at {@code offset}
   * of {@code values}.
   */
  void read(B buffer, float[] values, int offset, int numValues);

  /**
   * Multiplies by {@code vector} the {@code numRows} by {@code numColumns} row-major matrix that
   * begins {@code matrixOffset} values into {@code matrix}, writing one product per row into {@code
   * products}.
   */
  void multiply(
      int numRows, int numColumns, B matrix, long matrixOffset, B vector, B products);

  /**
   * Multiplies that same matrix by each of {@code numQueries} row-major vectors held end to end in
   * {@code queries}, writing each vector's {@code numRows} products end to end in {@code products},
   * in the order the vectors are given.
   *
   * <p>This is what batching queries is for, so an implementation whose library offers a
   * matrix-matrix multiply calls it here rather than looping over the single multiply.
   */
  void multiplyQueries(
      int numQueries, int numRows, int numColumns, B matrix, long matrixOffset, B queries,
      B products);
}
