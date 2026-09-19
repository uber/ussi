/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dense matrix-vector dot-product scorer over a native library.
 *
 * <p>The matrix is copied into native buffers once, one per chunk and in the same order, so a
 * chunk is multiplied in place. A score writes the query into a buffer, multiplies each chunk in
 * turn, and reads each chunk's products back into the caller's array.
 *
 * <p>The buffers a score needs are reused rather than allocated per score, since allocating them
 * each time routes every buffer through the allocator's process-wide bookkeeping, on which
 * concurrent scores contend. Scores execute concurrently, so a set of buffers is exclusive to one
 * score for its duration: it is acquired from a pool and returned when the score completes. The
 * pool is bounded by the core count, which also bounds the concurrent searches, so a score
 * normally acquires without waiting. Buffers are allocated on demand rather than up front,
 * because for a matrix of few columns they are a significant fraction of the matrix itself.
 */
final class NativeMatrixDotProductScorer<B> implements MatrixDotProductScorer {

  private final NativeBlas<B> blas;
  private final NativeBlasAdmission admission;
  private final DenseMatrix matrix;
  private final List<B> chunks;
  private final int maxNumRowsInAChunk;
  private final int maxNumScratches;
  private final BlockingQueue<Scratch<B>> availableScratches;
  private final Queue<Scratch<B>> allScratches = new ConcurrentLinkedQueue<>();
  private final AtomicInteger numScratchesCreated = new AtomicInteger();
  private volatile boolean closed;

  NativeMatrixDotProductScorer(DenseMatrix matrix, NativeBlas<B> blas) {
    this.blas = blas;
    this.admission = blas.getAdmission();
    this.matrix = matrix;
    this.chunks = new ArrayList<>(matrix.numChunks());
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      chunks.add(blas.allocate(matrix.chunk(chunk)));
    }
    this.maxNumRowsInAChunk = getMaxNumRowsInAChunk(matrix);
    this.maxNumScratches = Math.max(1, Runtime.getRuntime().availableProcessors());
    this.availableScratches = new ArrayBlockingQueue<>(maxNumScratches);
    this.closed = false;
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    if (closed) {
      throw new IllegalStateException("This scorer is already closed.");
    }
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    int dimension = matrix.dimension();
    // Held for the duration of the score, because the library's per-thread resource is held for
    // as long as the calling thread is inside it.
    admission.acquire();
    Scratch<B> scratch = takeScratch();
    try {
      blas.write(scratch.query, queryValues, dimension);
      for (int chunk = 0; chunk < chunks.size(); ++chunk) {
        int numRowsInChunk = matrix.numRowsInChunk(chunk);
        blas.multiply(
            numRowsInChunk, dimension, chunks.get(chunk), scratch.query, scratch.products);
        blas.read(scratch.products, dotProducts, matrix.firstRowInChunk(chunk), numRowsInChunk);
      }
    } finally {
      availableScratches.offer(scratch);
      admission.release();
    }
  }

  /**
   * Rows in the chunk holding the most of them. One products buffer of this size serves every
   * chunk, and it must be the maximum rather than any particular chunk, since a multiply writes
   * one product per row of its chunk and a smaller buffer would be written past its end.
   */
  static int getMaxNumRowsInAChunk(DenseMatrix matrix) {
    int widest = 0;
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      widest = Math.max(widest, matrix.numRowsInChunk(chunk));
    }
    return widest;
  }

  /** An available set of buffers, allocating one more while the bound permits. */
  private Scratch<B> takeScratch() {
    Scratch<B> reused = availableScratches.poll();
    if (reused != null) {
      return reused;
    }
    if (numScratchesCreated.incrementAndGet() <= maxNumScratches) {
      Scratch<B> created =
          new Scratch<>(blas.allocate(matrix.dimension()), blas.allocate(maxNumRowsInAChunk));
      allScratches.add(created);
      return created;
    }
    numScratchesCreated.decrementAndGet();
    try {
      return availableScratches.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a scoring buffer.", e);
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      for (B chunk : chunks) {
        blas.free(chunk);
      }
      for (Scratch<B> scratch : allScratches) {
        blas.free(scratch.query);
        blas.free(scratch.products);
      }
      allScratches.clear();
      availableScratches.clear();
    }
  }

  /** The buffers one score needs. Used by one score at a time, never shared concurrently. */
  private static final class Scratch<B> {
    private final B query;
    private final B products;

    Scratch(B query, B products) {
      this.query = query;
      this.products = products;
    }
  }
}
