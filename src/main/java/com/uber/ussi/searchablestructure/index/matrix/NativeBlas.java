/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/**
 * A native library that multiplies a matrix by a vector, and the buffers it multiplies out of.
 *
 * <p>Supporting a further library requires implementing this interface and nothing else. A dense
 * scorer allocates the buffers, reuses them across scores, traverses the matrix one chunk at a
 * time and admits its callers, none of which depends on the library in use. An implementation
 * supplies the buffer operations, the multiply, and the admission bounding its concurrent
 * callers.
 *
 * <p>{@code B} is the implementation's own buffer handle, which a caller of this interface only
 * passes back. A library addressing memory the process cannot dereference is therefore supported
 * on the same terms as one addressing memory it can.
 *
 * <p>An implementation is instantiated once per process rather than once per matrix, because the
 * resources it rations, and any thread count it maintains, are process-global.
 */
interface NativeBlas<B> {

  /** A buffer holding a copy of {@code values}, for the library to read repeatedly. */
  B allocate(float[] values);

  /** An empty buffer of {@code numValues}, for a caller to write and the library to read. */
  B allocate(int numValues);

  /** Releases a buffer this interface allocated. A buffer is released once. */
  void free(B buffer);

  /** Copies {@code numValues} of {@code values} into {@code buffer}. */
  void write(B buffer, float[] values, int numValues);

  /**
   * Copies {@code numValues} out of {@code buffer} into {@code values}, starting at {@code offset}
   * of {@code values}.
   */
  void read(B buffer, float[] values, int offset, int numValues);

  /**
   * Multiplies the {@code numRows} by {@code numColumns} row-major {@code matrix} by {@code
   * vector}, writing one product per row into {@code products}.
   */
  void multiply(int numRows, int numColumns, B matrix, B vector, B products);

  /**
   * Bounds the callers inside this library at once. One of these covers the whole library, so
   * every scorer over it is handed the same one, and a library rationing nothing hands out one
   * bounded by {@link Integer#MAX_VALUE}.
   */
  NativeBlasAdmission getAdmission();
}
