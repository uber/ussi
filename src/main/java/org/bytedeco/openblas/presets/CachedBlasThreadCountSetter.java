/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package org.bytedeco.openblas.presets;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;

/**
 * Sets the OpenBLAS thread count through a function pointer resolved once.
 *
 * <p>{@link openblas_nolapack#blas_set_num_threads} re-runs the library load and four symbol lookups
 * on every call in order to re-detect whether the vendor is OpenBLAS or MKL, which costs orders of
 * magnitude more than the native call it wraps. Resolving the symbol a single time leaves only the
 * call. The vendor cannot change while the process runs, so resolving once is equivalent.
 *
 * <p>Falls back to the standard binding when the OpenBLAS symbol is absent, which is how that
 * binding recognises a different vendor.
 *
 * <p>Declared in this package because the function pointer type has a protected constructor. Its
 * public constructor takes a {@link Pointer} but reinterprets the object's own address rather than
 * setting the target, so it cannot be used here.
 */
public final class CachedBlasThreadCountSetter {

  private static final openblas_nolapack.SetNumThreads FUNCTION = resolve();

  private CachedBlasThreadCountSetter() {}

  public static void setNumThreads(int numThreads) {
    if (FUNCTION == null) {
      openblas_nolapack.blas_set_num_threads(numThreads);
      return;
    }
    FUNCTION.call(numThreads);
  }

  private static openblas_nolapack.SetNumThreads resolve() {
    try {
      Loader.load(openblas_nolapack.class);
      Pointer address = Loader.addressof("openblas_set_num_threads");
      if (address == null || address.isNull()) {
        return null;
      }
      openblas_nolapack.SetNumThreads function = new openblas_nolapack.SetNumThreads();
      function.put(address);
      return function;
    } catch (LinkageError | RuntimeException e) {
      return null;
    }
  }
}
