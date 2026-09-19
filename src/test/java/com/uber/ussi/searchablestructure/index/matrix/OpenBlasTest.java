package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.searchablestructure.utils.parallel.ParallelismBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

/** What is specific to OpenBLAS: whether it loads, what it rations, and its thread count. */
class OpenBlasTest {
  private static final float DELTA = 1e-6f;

  @Test
  void isAvailableReturnsFalseOnUnsupportedPlatform() {
    assertFalse(OpenBlas.isAvailable(false, () -> {}));
  }

  @Test
  void isAvailableReturnsFalseWhenTheNativeProbeFails() {
    assertFalse(
        OpenBlas.isAvailable(
            true,
            () -> {
              throw new RuntimeException("native probe failed");
            }));
  }

  @Test
  void isAvailableReturnsTrueWhenTheNativeAndPointerProbesSucceed() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> assertTrue(OpenBlas.isAvailable(true, () -> fakeOpenBlas.numNativeLoadProbes++)));

    assertEquals(1, fakeOpenBlas.numNativeLoadProbes);
    assertEquals(1, fakeOpenBlas.numDeallocations);
  }

  /**
   * The probe loads native code, and a failing load is repeated for every dense index built
   * otherwise, which is a cost borne by an engine that ends up on the Java scorer.
   */
  @Test
  void availabilityIsProbedOncePerProcess() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          OpenBlas.forgetAvailability();
          OpenBlas.isAvailable();
          OpenBlas.isAvailable();
          OpenBlas.isAvailable();
        });

    assertEquals(
        OpenBlas.isSupportedPlatform() ? 1 : 0,
        fakeOpenBlas.numNativeLoadProbes,
        "availability must be memoized");
  }

  @Test
  void createScorerRejectsAnUnavailableLibrary() {
    assertThrows(
        IllegalStateException.class,
        () ->
            OpenBlas.createScorer(
                TestDenseMatrices.of(new float[] {1f}, 1, 1),
                /* availabilitySupplier */ () -> false));
  }

  /**
   * The thread count is process-global to OpenBLAS, so the budget applies it and a score must
   * leave it alone.
   */
  @Test
  void takesTheCurrentBudgetAtConstructionAndLeavesItAloneWhileScoring() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          OpenBlas blas = new OpenBlas();
          int numUpdatesAtConstruction = fakeOpenBlas.numThreadsUpdates.size();
          try (MatrixDotProductScorer scorer =
              new NativeMatrixDotProductScorer<>(
                  TestDenseMatrices.of(new float[] {1f}, 1, 1), blas)) {
            float[] dotProducts = new float[1];

            scorer.score(new float[] {2f}, dotProducts);
            scorer.score(new float[] {2f}, dotProducts);

            assertEquals(2f, dotProducts[0], DELTA);
          }

          assertEquals(
              numUpdatesAtConstruction,
              fakeOpenBlas.numThreadsUpdates.size(),
              "scoring must not modify the thread count");
        });

    // The budget, not the core count: it tracks the search concurrency, so the value at
    // construction depends on what else is running. Reading the library's maximum also moves the
    // count and restores it, so the budget's own value is asserted among the updates.
    assertTrue(
        fakeOpenBlas.numThreadsUpdates.contains(
            ParallelismBudget.shared().getNumThreadsPerSearch()),
        "the budget's count must be applied: " + fakeOpenBlas.numThreadsUpdates);
  }

  /** A machine can have more cores than the binary retains buffers for, so neither is exceeded. */
  @Test
  void boundsCallersByBothTheCoresAndWhatTheBinaryRetainsBuffersFor() {
    int maxNumConcurrentCallers = OpenBlas.shared().getAdmission().getMaxNumConcurrentCallers();

    assertTrue(maxNumConcurrentCallers >= 1, "at least one caller must be admitted");
    assertTrue(
        maxNumConcurrentCallers <= Runtime.getRuntime().availableProcessors(),
        "admitted " + maxNumConcurrentCallers + " callers on a machine of fewer cores");
    assertTrue(
        maxNumConcurrentCallers <= OpenBlas.readMaxNumThreads(),
        "admitted " + maxNumConcurrentCallers + " callers with fewer buffers than that");
  }

  /** Reading the number must restore whatever number was configured beforehand. */
  @Test
  void readingTheMaxNumThreadsIsRepeatable() {
    assertEquals(OpenBlas.readMaxNumThreads(), OpenBlas.readMaxNumThreads());
  }

  @Test
  void sharedReturnsOneInstanceForTheProcess() {
    assertEquals(OpenBlas.shared(), OpenBlas.shared());
  }

  static void withFakeOpenBlas(FakeOpenBlas fakeOpenBlas, Runnable runnable) {
    OpenBlas.FloatArrayPointerFactory originalArrayPointerFactory =
        OpenBlas.floatArrayPointerFactory;
    OpenBlas.FloatSizePointerFactory originalSizePointerFactory = OpenBlas.floatSizePointerFactory;
    OpenBlas.FloatPointerDeallocator originalDeallocator = OpenBlas.floatPointerDeallocator;
    OpenBlas.FloatPointerArrayReader originalArrayReader = OpenBlas.floatPointerArrayReader;
    OpenBlas.SgemvOperation originalSgemvOperation = OpenBlas.sgemvOperation;
    OpenBlas.FloatPointerArrayWriter originalArrayWriter = OpenBlas.floatPointerArrayWriter;
    Runnable originalNativeLoadProbe = OpenBlas.blasNativeLoadProbe;
    IntConsumer originalThreadCountSetter = OpenBlas.blasNumThreadsSetter;
    try {
      OpenBlas.floatArrayPointerFactory = values -> null;
      OpenBlas.floatSizePointerFactory = size -> null;
      OpenBlas.floatPointerDeallocator = pointer -> fakeOpenBlas.numDeallocations++;
      OpenBlas.floatPointerArrayReader =
          (pointer, values, offset, length) -> values[offset] = 2f;
      OpenBlas.floatPointerArrayWriter = (pointer, values, length) -> fakeOpenBlas.numQueryWrites++;
      OpenBlas.sgemvOperation =
          (order,
              transA,
              numRowsA,
              numColsA,
              alpha,
              matrix,
              lda,
              query,
              incX,
              beta,
              dotProducts,
              incY) -> fakeOpenBlas.numMultiplies++;
      OpenBlas.blasNativeLoadProbe = () -> fakeOpenBlas.numNativeLoadProbes++;
      OpenBlas.blasNumThreadsSetter = numThreads -> fakeOpenBlas.numThreadsUpdates.add(numThreads);
      runnable.run();
    } finally {
      OpenBlas.floatArrayPointerFactory = originalArrayPointerFactory;
      OpenBlas.floatSizePointerFactory = originalSizePointerFactory;
      OpenBlas.floatPointerDeallocator = originalDeallocator;
      OpenBlas.floatPointerArrayReader = originalArrayReader;
      OpenBlas.floatPointerArrayWriter = originalArrayWriter;
      OpenBlas.sgemvOperation = originalSgemvOperation;
      OpenBlas.blasNativeLoadProbe = originalNativeLoadProbe;
      OpenBlas.blasNumThreadsSetter = originalThreadCountSetter;
      OpenBlas.forgetAvailability();
    }
  }

  static final class FakeOpenBlas {
    int numNativeLoadProbes;
    int numDeallocations;
    int numMultiplies;
    int numQueryWrites;
    final List<Integer> numThreadsUpdates = new ArrayList<>();
  }
}
