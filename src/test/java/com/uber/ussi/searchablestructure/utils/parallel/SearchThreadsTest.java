/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchThreadsTest {

  @AfterEach
  void shutDownThePool() {
    SearchThreads.shutdown();
  }

  @Test
  void runsOneWorkUnitOnTheCallingThread() {
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(1, workUnit -> ran.incrementAndGet());
    assertEquals(1, ran.get());
  }

  @Test
  void runsEveryWorkUnitAndReturnsOnceAllAreFinished() {
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(4, workUnit -> ran.incrementAndGet());
    assertEquals(4, ran.get());
  }

  @Test
  void shutdownReleasesThePoolSoTheNextUseCreatesAFreshOne() {
    SearchThreads.runInParallel(2, workUnit -> {});
    SearchThreads.shutdown();
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
  }

  @Test
  void resizeChangesThePoolSize() {
    SearchThreads.resize(2);
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
    SearchThreads.resize(4);
    ran.set(0);
    SearchThreads.runInParallel(4, workUnit -> ran.incrementAndGet());
    assertEquals(4, ran.get());
  }
}
