/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import static org.bytedeco.openblas.global.openblas.CblasNoTrans;
import static org.bytedeco.openblas.global.openblas.CblasRowMajor;

import com.uber.ussi.searchablestructure.parallel.ParallelismBudget;
import com.uber.ussi.utils.Utils;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import javax.annotation.Nullable;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.openblas.global.openblas;
import org.bytedeco.openblas.global.openblas_full;
import org.bytedeco.openblas.presets.CachedBlasThreadCountSetter;
import org.bytedeco.openblas.presets.openblas_nolapack;

/**
 * OpenBLAS behind {@link NativeBlas}, reached over JNI and addressing native memory through {@link
 * FloatPointer}.
 *
 * <p>This class holds everything specific to OpenBLAS: which platforms carry a binary, whether
 * that binary loads, the thread count the library keeps for the whole process, and the bound on
 * concurrent callers its per-thread buffers impose. A dense scorer reads none of it.
 *
 * <p>One instance serves the process, because the thread count and the buffers it rations are the
 * library's rather than any one matrix's.
 */
final class OpenBlas implements NativeBlas<FloatPointer> {

  /**
   * Applied when the loaded binary reports no maximum. Every binary observed so far retains at
   * least this many buffers, so a machine whose number cannot be read remains bounded.
   */
  private static final int FALLBACK_MAX_NUM_CONCURRENT_CALLERS = 64;

  /**
   * One entry per native binary carried as a runtime dependency. {@link #isAvailable()} also
   * probes the load, since carrying a binary is weaker than loading one.
   */
  private static final boolean IS_SUPPORTED_PLATFORM =
      (Utils.isRunningOnLinux() && Utils.isRunningOnArm())
          || (Utils.isRunningOnLinux() && Utils.isRunningOnX86())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnArm())
          || (Utils.isRunningOnMacOs() && Utils.isRunningOnX86());

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
   * Memoized because the probe loads native code, and a failing load is repeated for every dense
   * index built otherwise. Availability does not change within a process.
   */
  @Nullable private static volatile Boolean isAvailable;

  @Nullable private static volatile OpenBlas shared;

  private final NativeBlasAdmission admission;

  OpenBlas() {
    this.admission =
        new NativeBlasAdmission(
            Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), readMaxNumThreads()));
    // The count is process-global to OpenBLAS and rebuilds its thread pool, so it cannot be chosen
    // per score, where it would cost orders of magnitude more than the multiply itself. The budget
    // applies it instead, whenever the search concurrency changes.
    ParallelismBudget.shared().onChange(numThreads -> blasNumThreadsSetter.accept(numThreads));
  }

  /** The instance for this process, constructed on first use. */
  static OpenBlas shared() {
    OpenBlas current = shared;
    if (current == null) {
      synchronized (OpenBlas.class) {
        current = shared;
        if (current == null) {
          current = new OpenBlas();
          shared = current;
        }
      }
    }
    return current;
  }

  static boolean isAvailable() {
    Boolean memoized = isAvailable;
    if (memoized == null) {
      memoized = isAvailable(IS_SUPPORTED_PLATFORM, blasNativeLoadProbe);
      isAvailable = memoized;
    }
    return memoized;
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
      // Tiny native probes force JavaCPP to load the JNI and native OpenBLAS and pointer bits.
      nativeLoadProbe.run();
      FloatPointer pointerProbe = floatSizePointerFactory.create(1);
      floatPointerDeallocator.deallocate(pointerProbe);
      return true;
    } catch (LinkageError | RuntimeException e) {
      return false;
    }
  }

  /**
   * A dense scorer over this library.
   *
   * @throws IllegalStateException if the library is unavailable, since a scorer over an
   *     unavailable library would fail on its first allocation rather than here.
   */
  static MatrixDotProductScorer createScorer(
      DenseMatrix matrix, BooleanSupplier availabilitySupplier) {
    if (!availabilitySupplier.getAsBoolean()) {
      throw new IllegalStateException("OpenBLAS is not available on this platform.");
    }
    return new NativeMatrixDotProductScorer<>(matrix, shared());
  }

  /** Discards the memoized availability, so a test may vary what the probes report. */
  static void forgetAvailability() {
    isAvailable = null;
  }

  @Override
  public NativeBlasAdmission getAdmission() {
    return admission;
  }

  @Override
  public FloatPointer allocate(float[] values) {
    return floatArrayPointerFactory.create(values);
  }

  @Override
  public FloatPointer allocate(int numValues) {
    return floatSizePointerFactory.create(numValues);
  }

  @Override
  public void free(FloatPointer buffer) {
    floatPointerDeallocator.deallocate(buffer);
  }

  @Override
  public void write(FloatPointer buffer, float[] values, int numValues) {
    floatPointerArrayWriter.write(buffer, values, numValues);
  }

  @Override
  public void read(FloatPointer buffer, float[] values, int offset, int numValues) {
    floatPointerArrayReader.read(buffer, values, offset, numValues);
  }

  @Override
  public void multiply(
      int numRows,
      int numColumns,
      FloatPointer matrix,
      FloatPointer vector,
      FloatPointer products) {
    sgemvOperation.run(
        /* Order */ CblasRowMajor,
        /* transA */ CblasNoTrans,
        /* numRowsA */ numRows,
        /* numColsA */ numColumns,
        /* alpha */ 1.0f,
        /* A */ matrix,
        /* lda */ numColumns,
        /* X */ vector,
        /* incX */ 1,
        /* beta */ 0.0f,
        /* Y */ products,
        /* incY */ 1);
  }

  /**
   * The number of threads the loaded binary retains buffers for.
   *
   * <p>OpenBLAS retains one buffer per thread that calls into it, and the number of buffers is
   * fixed when the binary is built. A caller beyond that number reaches an allocation the library
   * reports as liable to corrupt the heap rather than one that fails, so the concurrent callers
   * are bounded by this number.
   *
   * <p>Obtained by requesting more threads than any binary provides, since OpenBLAS answers a
   * request above its own maximum with that maximum. The number configured beforehand is restored,
   * so obtaining it leaves the library as it was found.
   */
  static int readMaxNumThreads() {
    try {
      int configuredNumThreads = openblas_full.openblas_get_num_threads();
      blasNumThreadsSetter.accept(Integer.MAX_VALUE);
      int maxNumThreads = openblas_full.openblas_get_num_threads();
      if (configuredNumThreads > 0) {
        blasNumThreadsSetter.accept(configuredNumThreads);
      }
      return maxNumThreads > 0 ? maxNumThreads : FALLBACK_MAX_NUM_CONCURRENT_CALLERS;
    } catch (LinkageError | RuntimeException e) {
      return FALLBACK_MAX_NUM_CONCURRENT_CALLERS;
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
