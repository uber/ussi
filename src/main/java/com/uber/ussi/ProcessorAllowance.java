/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * The processors USSI may use, which a host sharing one JVM with it may lower at runtime.
 *
 * <p>The operating system limits a process to a share of a machine through a processor set or a
 * bandwidth quota, and {@link Runtime#availableProcessors()} reports that share. Those limits
 * work at process granularity, so they cannot divide processors between USSI and a host that
 * shares its JVM. This allowance does: a host sets it below the processors the process may use, and
 * USSI sizes its admission, parallelism budget, and search pool from it.
 *
 * <p>The allowance is a supplier rather than a constant, so a host may shrink USSI's share while it
 * is busy with other work. A static number cannot track a bursty host. A change is applied under a
 * moment with no search running, so no outstanding multiply is torn down.
 *
 * <p>The allowance is clamped by the processors the process may run on, so a supplier that exceeds
 * that bound is reduced to it. A native BLAS library may register a further clamp, since the
 * per-thread buffers it retains are fixed when the binary is built and a caller beyond that number
 * faults rather than fails. Until such a library loads, no clamp beyond the operating system bound
 * is in effect.
 *
 * <p>Setting the allowance at or above the host's own pool size makes USSI's admission semaphore
 * stop binding, so only one gate governs concurrency. USSI blocks the caller's thread, and two
 * nested admission limits turn back-pressure into rejections in a host with a bounded pool.
 *
 * <p>A processor count is not NUMA support. Memory locality needs thread and allocation affinity,
 * which the JVM cannot provide without native help, so it has to come from the launcher.
 */
public final class ProcessorAllowance {

  private static volatile ProcessorAllowance shared = new ProcessorAllowance();

  private volatile IntSupplier supplier;
  private volatile IntSupplier nativeMaxProcessorsSupplier;
  private volatile int current;

  private ProcessorAllowance() {
    this.supplier = () -> Math.max(1, Runtime.getRuntime().availableProcessors());
    this.nativeMaxProcessorsSupplier = () -> Integer.MAX_VALUE;
    this.current = clamp();
  }

  /** The instance for this process. */
  public static ProcessorAllowance shared() {
    return shared;
  }

  /**
   * Sets the supplier the host uses to lower or raise the allowance at runtime.
   *
   * <p>{@link #getNumProcessors()} reports the new value as soon as this returns. Propagating it to
   * the admission semaphore, the parallelism budget, and the search pool is deferred: those follow
   * on the budget's own cadence, which re-derives once per averaging window rather than once per
   * sample, and only while at least one index is open. A namespace built after this call reads the
   * new value directly.
   */
  public void setNumProcessors(IntSupplier supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
    refresh();
  }

  /**
   * Registers the bound a native library imposes, since its per-thread buffers are fixed at build
   * time. Registered by the BLAS adapter when it loads, so the allowance is bounded by the buffers
   * the loaded binary retains. Until a library registers, no native clamp is in effect.
   */
  public void setNativeMaxProcessorsSupplier(IntSupplier supplier) {
    this.nativeMaxProcessorsSupplier = Objects.requireNonNull(supplier, "supplier");
  }

  /** The processors USSI may use, clamped by the operating system and any registered native bound. */
  public int getNumProcessors() {
    return current;
  }

  /** Re-reads the supplier and re-clamps. Called by the budget sampler and after a host change. */
  public void refresh() {
    current = clamp();
  }

  private int clamp() {
    int requested = Math.max(1, supplier.getAsInt());
    int available = Math.max(1, Runtime.getRuntime().availableProcessors());
    int nativeMax = Math.max(1, nativeMaxProcessorsSupplier.getAsInt());
    return Math.min(Math.min(requested, available), nativeMax);
  }

  /** Resets the shared instance to its default, so a test may vary the allowance in isolation. */
  static void resetForTests() {
    shared = new ProcessorAllowance();
  }
}
