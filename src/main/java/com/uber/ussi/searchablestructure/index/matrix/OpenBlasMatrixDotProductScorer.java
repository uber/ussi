/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import static org.bytedeco.openblas.global.openblas.CblasNoTrans;
import static org.bytedeco.openblas.global.openblas.CblasRowMajor;

import com.uber.ussi.searchablestructure.ParallelismBudget;
import com.uber.ussi.utils.Utils;
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
  static SgemvOperation sgemvOperation = openblas::cblas_sgemv;
  static Runnable blasNativeLoadProbe = openblas_nolapack::blas_get_num_threads;
  static IntConsumer blasThreadCountSetter = CachedBlasThreadCountSetter::setNumThreads;

  // One entry per native binary carried as a runtime dependency; isAvailable still probes the load.
  private static final boolean IS_SUPPORTED_PLATFORM =
      (Utils.isRunningOnLinux() && Utils.isRunningOnArm())
          || (Utils.isRunningOnLinux() && Utils.isRunningOnX86())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnArm())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnX86());

  // One native copy per chunk, in the same order, so a chunk can be multiplied where it lies.
  private final FloatPointer[] nativeChunks;
  private final DenseMatrix matrix;
  private boolean closed;

  OpenBlasMatrixDotProductScorer(DenseMatrix matrix, BooleanSupplier availabilitySupplier) {
    if (!availabilitySupplier.getAsBoolean()) {
      throw new IllegalStateException("OpenBLAS is not available on this platform.");
    }
    // The count is process-global to OpenBLAS and rebuilds its thread pool, so it cannot be chosen
    // per score, where it would cost orders of magnitude more than the gemv itself. The budget
    // applies it instead, whenever the search concurrency changes.
    ParallelismBudget.shared().onChange(blasThreadCountSetter::accept);
    this.matrix = matrix;
    this.nativeChunks = new FloatPointer[matrix.numChunks()];
    for (int chunk = 0; chunk < nativeChunks.length; ++chunk) {
      nativeChunks[chunk] = floatArrayPointerFactory.create(matrix.chunk(chunk));
    }
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
    try (FloatPointer nativeQuery = floatArrayPointerFactory.create(queryValues)) {
      for (int chunk = 0; chunk < nativeChunks.length; ++chunk) {
        int rowsInChunk = matrix.numRowsInChunk(chunk);
        try (FloatPointer nativeDotProducts = floatSizePointerFactory.create(rowsInChunk)) {
          sgemvOperation.run(
              /* Order */ CblasRowMajor,
              /* transA */ CblasNoTrans,
              /* numRowsA */ rowsInChunk,
              /* numColsA */ dimension,
              /* alpha */ 1.0f,
              /* A */ nativeChunks[chunk],
              /* lda */ dimension,
              /* X */ nativeQuery,
              /* incX */ 1,
              /* beta */ 0.0f,
              /* Y */ nativeDotProducts,
              /* incY */ 1);
          floatPointerArrayReader.read(
              nativeDotProducts, dotProducts, matrix.firstRowInChunk(chunk), rowsInChunk);
        }
      }
    }
  }

  @Override
  public void close() {
    if (!closed) {
      for (FloatPointer nativeChunk : nativeChunks) {
        floatPointerDeallocator.deallocate(nativeChunk);
      }
      closed = true;
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
