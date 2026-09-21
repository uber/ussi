/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

/**
 * An estimate of the memory one namespace holds, separating what the Java heap holds from what
 * native or device buffers hold.
 *
 * <p>The on-heap estimate counts the arrays and maps the index retains. The native estimate counts
 * the buffers a scorer allocated outside the heap. Both are estimates: the on-heap figure omits
 * the per-object overhead the JVM carries, and the native figure omits the per-thread buffers a
 * loaded BLAS library retains for the whole process, since those are not owned by any one index.
 *
 * <p>Deletes do not shrink the estimate. A deleted row keeps its place in the matrix until the
 * index is rebuilt, so the estimate reflects allocated rows rather than live ones.
 */
public final class MemoryFootprint {

  private final long onHeapBytes;
  private final long nativeBytes;

  public MemoryFootprint(long onHeapBytes, long nativeBytes) {
    this.onHeapBytes = onHeapBytes;
    this.nativeBytes = nativeBytes;
  }

  /** The bytes the Java heap holds for this namespace. */
  public long getOnHeapBytes() {
    return onHeapBytes;
  }

  /** The bytes native or device buffers hold for this namespace. */
  public long getNativeBytes() {
    return nativeBytes;
  }
}
