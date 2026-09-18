/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import static org.bytedeco.openblas.global.openblas.CblasNoTrans;
import static org.bytedeco.openblas.global.openblas.CblasRowMajor;

import com.uber.ussi.searchablestructure.parallel.ParallelismBudget;
import com.uber.ussi.utils.Utils;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.openblas.global.openblas;
import org.bytedeco.openblas.presets.CachedBlasThreadCountSetter;
import org.bytedeco.openblas.presets.openblas_nolapack;

/** JNI-backed OpenBLAS dense matrix-vector dot-product scorer. */
final class OpenBlasMatrixDotProductScorer implements MatrixDotProductScorer {
  static FloatArrayPointerFactory floatArrayPointerFactory = FloatPointer::new;
  static FloatSizePointerFactory floatSizePointerFactory = FloatPointer::new;
  static FloatPointerDeallocator floatPointerDeallocator = FloatPointer::deallocate;
  static FloatPointerArrayReader floatPointerArrayReader = FloatPointer::get;
  static FloatPointerArrayWriter floatPointerArrayWriter =
      (pointer, values, length) -> pointer.put(values, 0, length);
  static SgemvOperation sgemvOperation = openblas::cblas_sgemv;
  static Runnable blasNativeLoadProbe = openblas_nolapack::blas_get_num_threads;
  static IntConsumer blasNumThreadsSetter = CachedBlasThreadCountSetter::setNumThreads;

  /**
   * One entry per native binary carried as a runtime dependency. {@link #isAvailable()} still
   * probes the load.
   */
  private static final boolean IS_SUPPORTED_PLATFORM =
      (Utils.isRunningOnLinux() && Utils.isRunningOnArm())
          || (Utils.isRunningOnLinux() && Utils.isRunningOnX86())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnArm())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnX86());

  // One native copy per chunk, in the same order, so a chunk can be multiplied where it lies.
  private final FloatPointer[] nativeChunks;
  private final DenseMatrix matrix;
  // The native buffers a score needs, reused rather than allocated per score. Allocating them each
  // time puts every pointer through the pointer library's process-wide bookkeeping, which several
  // scores at once contend on. Scores run concurrently, so a buffer belongs to one score at a time:
  // it is taken from here and returned when the score finishes.
  private final int maxRowsInAChunk;
  private final int maxScratches;
  private final BlockingQueue<Scratch> availableScratches;
  private final Queue<Scratch> allScratches = new ConcurrentLinkedQueue<>();
  private final AtomicInteger scratchesCreated = new AtomicInteger();
  private volatile boolean closed;

  OpenBlasMatrixDotProductScorer(DenseMatrix matrix, BooleanSupplier availabilitySupplier) {
    if (!availabilitySupplier.getAsBoolean()) {
      throw new IllegalStateException("OpenBLAS is not available on this platform.");
    }
    // The count is process-global to OpenBLAS and rebuilds its thread pool, so it cannot be chosen
    // per score, where it would cost orders of magnitude more than the gemv itself. The budget
    // applies it instead, whenever the search concurrency changes.
    ParallelismBudget.shared().onChange(blasNumThreadsSetter::accept);
    this.matrix = matrix;
    this.nativeChunks = new FloatPointer[matrix.numChunks()];
    for (int chunk = 0; chunk < nativeChunks.length; ++chunk) {
      nativeChunks[chunk] = floatArrayPointerFactory.create(matrix.chunk(chunk));
    }
    this.maxRowsInAChunk = widestChunk(matrix);
    // Matches the bound on concurrent searches, so a score never waits for a buffer. They are
    // created on demand rather than up front, because for a matrix of few columns the buffers are
    // a noticeable fraction of the matrix itself.
    this.maxScratches = Math.max(1, Runtime.getRuntime().availableProcessors());
    this.availableScratches = new ArrayBlockingQueue<>(maxScratches);
    this.closed = false;
  }

  static boolean isAvailable() {
    return isAvailable(IS_SUPPORTED_PLATFORM, blasNativeLoadProbe);
  }

  /** Whether a native binary is carried for this platform, which is short of it having loaded. */
  static boolean isSupportedPlatform() {
    return IS_SUPPORTED_PLATFORM;
  }

  static boolean isAvailable(boolean isSupportedPlatform, Runnable nativeLoadProbe) {
    if (!isSupportedPlatform) {
      return false;
    }
    try {
      // Tiny native probes force JavaCPP to load the JNI/native OpenBLAS and pointer bits.
      nativeLoadProbe.run();
      FloatPointer pointerProbe = floatSizePointerFactory.create(1);
      floatPointerDeallocator.deallocate(pointerProbe);
      return true;
    } catch (LinkageError | RuntimeException e) {
      return false;
    }
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    if (closed) {
      throw new IllegalStateException("OpenBLAS scorer is already closed.");
    }
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    int dimension = matrix.dimension();
    // Held for the whole score, because a buffer belongs to the thread for as long as it is inside
    // the library.
    OpenBlasAdmission.acquire();
    Scratch scratch = takeScratch();
    try {
      floatPointerArrayWriter.write(scratch.query, queryValues, dimension);
      for (int chunk = 0; chunk < nativeChunks.length; ++chunk) {
        int rowsInChunk = matrix.numRowsInChunk(chunk);
        sgemvOperation.run(
            /* Order */ CblasRowMajor,
            /* transA */ CblasNoTrans,
            /* numRowsA */ rowsInChunk,
            /* numColsA */ dimension,
            /* alpha */ 1.0f,
            /* A */ nativeChunks[chunk],
            /* lda */ dimension,
            /* X */ scratch.query,
            /* incX */ 1,
            /* beta */ 0.0f,
            /* Y */ scratch.scores,
            /* incY */ 1);
        floatPointerArrayReader.read(
            scratch.scores, dotProducts, matrix.firstRowInChunk(chunk), rowsInChunk);
      }
    } finally {
      availableScratches.offer(scratch);
      OpenBlasAdmission.release();
    }
  }

  /**
   * Rows in the chunk holding the most of them. One score buffer of this size serves every chunk,
   * and it must be the widest rather than any particular one, since a multiply writes a row per
   * row of its chunk and a short buffer would be written past its end.
   */
  static int widestChunk(DenseMatrix matrix) {
    int widest = 0;
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      widest = Math.max(widest, matrix.numRowsInChunk(chunk));
    }
    return widest;
  }

  /**
   * A free set of buffers, making one more if the bound allows. Waiting cannot normally happen,
   * since concurrent searches are bounded by the same number.
   */
  private Scratch takeScratch() {
    Scratch reused = availableScratches.poll();
    if (reused != null) {
      return reused;
    }
    if (scratchesCreated.incrementAndGet() <= maxScratches) {
      Scratch created = new Scratch(matrix.dimension(), maxRowsInAChunk);
      allScratches.add(created);
      return created;
    }
    scratchesCreated.decrementAndGet();
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
      for (FloatPointer nativeChunk : nativeChunks) {
        floatPointerDeallocator.deallocate(nativeChunk);
      }
      for (Scratch scratch : allScratches) {
        floatPointerDeallocator.deallocate(scratch.query);
        floatPointerDeallocator.deallocate(scratch.scores);
      }
      allScratches.clear();
      availableScratches.clear();
    }
  }

  /** The buffers one score needs. Used by one score at a time, never shared concurrently. */
  private static final class Scratch {
    private final FloatPointer query;
    private final FloatPointer scores;

    Scratch(int dimension, int maxRowsInAChunk) {
      this.query = floatSizePointerFactory.create(dimension);
      this.scores = floatSizePointerFactory.create(maxRowsInAChunk);
    }
  }

  interface FloatArrayPointerFactory {
    FloatPointer create(float[] values);
  }

  interface FloatSizePointerFactory {
    FloatPointer create(int size);
  }

  interface FloatPointerDeallocator {
    void deallocate(FloatPointer pointer);
  }

  interface FloatPointerArrayReader {
    void read(FloatPointer pointer, float[] values, int offset, int length);
  }

  interface FloatPointerArrayWriter {
    void write(FloatPointer pointer, float[] values, int length);
  }

  interface SgemvOperation {
    void run(
        int order,
        int transA,
        int numRowsA,
        int numColsA,
        float alpha,
        FloatPointer matrix,
        int lda,
        FloatPointer query,
        int incX,
        float beta,
        FloatPointer dotProducts,
        int incY);
  }
}
