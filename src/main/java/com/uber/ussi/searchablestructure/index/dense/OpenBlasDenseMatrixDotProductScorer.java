/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.dense;

import static org.bytedeco.openblas.global.openblas.CblasNoTrans;
import static org.bytedeco.openblas.global.openblas.CblasRowMajor;

import com.uber.ussi.utils.Utils;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.openblas.global.openblas;
import org.bytedeco.openblas.presets.openblas_nolapack;

/** JNI-backed OpenBLAS dense matrix-vector dot-product scorer. */
final class OpenBlasDenseMatrixDotProductScorer implements DenseMatrixDotProductScorer {
  static FloatArrayPointerFactory floatArrayPointerFactory = FloatPointer::new;
  static FloatSizePointerFactory floatSizePointerFactory = FloatPointer::new;
  static FloatPointerDeallocator floatPointerDeallocator = FloatPointer::deallocate;
  static FloatPointerArrayReader floatPointerArrayReader = FloatPointer::get;
  static SgemvOperation sgemvOperation = openblas::cblas_sgemv;
  static Runnable blasNativeLoadProbe = openblas_nolapack::blas_get_num_threads;
  static IntSupplier blasThreadCountSupplier = openblas_nolapack::blas_get_num_threads;
  static IntConsumer blasThreadCountSetter = openblas_nolapack::blas_set_num_threads;

  private static final boolean IS_SUPPORTED_PLATFORM =
      (Utils.isRunningOnLinux() && Utils.isRunningOnArm())
          || (Utils.isRunningOnLinux() && Utils.isRunningOnX86())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnX86());
  private static final Object OPENBLAS_GEMV_LOCK = new Object();
  private static final int GEMV_NUM_THREADS =
      Math.max(1, Runtime.getRuntime().availableProcessors());

  private final FloatPointer nativeMatrix;
  private final int numRows;
  private final int dimension;
  private boolean closed;

  OpenBlasDenseMatrixDotProductScorer(
      float[] rowMajorValues, int numRows, int dimension, BooleanSupplier availabilitySupplier) {
    DenseMatrixDotProductScorers.validateMatrix(rowMajorValues, numRows, dimension);
    if (!availabilitySupplier.getAsBoolean()) {
      throw new IllegalStateException("OpenBLAS is not available on this platform.");
    }
    this.nativeMatrix = floatArrayPointerFactory.create(rowMajorValues);
    this.numRows = numRows;
    this.dimension = dimension;
    this.closed = false;
  }

  static boolean isAvailable() {
    return isAvailable(IS_SUPPORTED_PLATFORM, blasNativeLoadProbe);
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
    DenseMatrixDotProductScorers.validateScoreInputs(
        null, numRows, dimension, queryValues, dotProducts);
    try (FloatPointer nativeQuery = floatArrayPointerFactory.create(queryValues);
        FloatPointer nativeDotProducts = floatSizePointerFactory.create(numRows)) {
      synchronized (OPENBLAS_GEMV_LOCK) {
        int previousThreads = blasThreadCountSupplier.getAsInt();
        blasThreadCountSetter.accept(GEMV_NUM_THREADS);
        try {
          sgemvOperation.run(
              /* Order */ CblasRowMajor,
              /* transA */ CblasNoTrans,
              /* numRowsA */ numRows,
              /* numColsA */ dimension,
              /* alpha */ 1.0f,
              /* A */ nativeMatrix,
              /* lda */ dimension,
              /* X */ nativeQuery,
              /* incX */ 1,
              /* beta */ 0.0f,
              /* Y */ nativeDotProducts,
              /* incY */ 1);
        } finally {
          blasThreadCountSetter.accept(previousThreads);
        }
      }
      floatPointerArrayReader.read(nativeDotProducts, dotProducts);
    }
  }

  @Override
  public void close() {
    if (!closed) {
      floatPointerDeallocator.deallocate(nativeMatrix);
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
    void read(FloatPointer pointer, float[] values);
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
