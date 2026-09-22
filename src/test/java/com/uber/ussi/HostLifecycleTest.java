/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.error.SearchCancelledException;
import com.uber.ussi.searchablestructure.utils.parallel.SearchThreads;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a host does to a namespace while it is serving, which is where the process-wide structures
 * meet a live search rather than a fixture.
 */
class HostLifecycleTest {

  private static final int NUM_ROWS = 400;
  private static final int DIMENSION = 16;

  private int originalAdmissionBound;

  @AfterEach
  void leaveTheProcessAsItWasFound() {
    ProcessorAllowance.shared().setNumProcessors(() -> Runtime.getRuntime().availableProcessors());
    if (originalAdmissionBound > 0) {
      QueryAdmission.shared()
          .runExclusively(
              () -> QueryAdmission.shared().setMaxNumConcurrentSearches(originalAdmissionBound));
      originalAdmissionBound = 0;
    }
    SearchThreads.shutdown();
  }

  /**
   * A host cancelling a search interrupts the thread it runs on. The search it interrupts reports
   * the cancellation and releases what it held, so the namespace answers and accepts writes after.
   */
  @Test
  void cancellingASearchLeavesTheNamespaceServing() throws InterruptedException {
    try (NearestNeighborSearchIndex index = populatedNamespace()) {
      CountDownLatch searching = new CountDownLatch(1);
      List<String> outcome = Collections.synchronizedList(new ArrayList<>());
      List<Boolean> interruptRestored = Collections.synchronizedList(new ArrayList<>());
      Thread searcher =
          new Thread(
              () -> {
                try {
                  for (int search = 0; search < 10_000; ++search) {
                    searching.countDown();
                    index.getNearestNeighborRowNums(5, query(0), MetaFilter.empty());
                  }
                  outcome.add("completed");
                } catch (SearchCancelledException e) {
                  outcome.add("cancelled");
                  interruptRestored.add(Thread.currentThread().isInterrupted());
                }
              });
      searcher.setDaemon(true);
      searcher.start();
      searching.await();

      searcher.interrupt();
      searcher.join();

      assertEquals(List.of("cancelled"), outcome);
      assertEquals(List.of(Boolean.TRUE), interruptRestored, "the interrupt is restored");

      // A namespace that leaked its read lock would block either of these for ever.
      long inserted = index.insert(vector(NUM_ROWS), Map.of("city", "sf"));
      assertEquals(NUM_ROWS + 1, index.size());
      assertEquals(
          inserted,
          index.getNearestNeighborRowNums(1, query(NUM_ROWS), MetaFilter.empty()).getRowNum(0));
    }
  }

  /**
   * A host lowering the allowance while searches run has the change applied once they finish, since
   * the admission it resizes is what those searches hold. The searches themselves are unaffected.
   */
  @Test
  void loweringTheAllowanceWhileSearchesRunWaitsForThem() throws InterruptedException {
    originalAdmissionBound = QueryAdmission.shared().getMaxNumConcurrentSearches();
    try (NearestNeighborSearchIndex index = populatedNamespace()) {
      AtomicBoolean keepSearching = new AtomicBoolean(true);
      AtomicInteger numSearches = new AtomicInteger();
      List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch searching = new CountDownLatch(3);
      List<Thread> searchers = new ArrayList<>();
      for (int searcher = 0; searcher < 3; ++searcher) {
        searchers.add(
            startSearcher(index, keepSearching, numSearches, failures, searching));
      }
      searching.await();

      // The path the budget takes once it observes a lowered allowance: reach a moment with no
      // search running, and resize the admission inside it.
      ProcessorAllowance.shared().setNumProcessors(() -> 2);
      QueryAdmission.shared()
          .runExclusively(() -> QueryAdmission.shared().setMaxNumConcurrentSearches(2));

      assertEquals(2, QueryAdmission.shared().getMaxNumConcurrentSearches());

      keepSearching.set(false);
      for (Thread searcher : searchers) {
        searcher.join();
      }

      assertEquals(List.of(), failures, "no search failed while the allowance was lowered");
      assertTrue(numSearches.get() >= 3, "every searcher ran, saw " + numSearches.get());
      // The lowered bound governs from here, and searches still complete under it.
      assertEquals(5, index.getNearestNeighborRowNums(5, query(0), MetaFilter.empty()).size());
    }
  }

  /**
   * A host unloading the library shuts the search pool down. The shutdown suspends every search
   * first, so no work unit is cancelled underneath the search that submitted it, and a namespace
   * still serving afterwards creates a pool again.
   */
  @Test
  void shuttingDownTheSearchPoolUnderLoadLetsEverySearchFinish() throws InterruptedException {
    try (NearestNeighborSearchIndex index = populatedNamespace()) {
      AtomicBoolean keepSearching = new AtomicBoolean(true);
      AtomicInteger numSearches = new AtomicInteger();
      List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch searching = new CountDownLatch(3);
      List<Thread> searchers = new ArrayList<>();
      for (int searcher = 0; searcher < 3; ++searcher) {
        searchers.add(
            startSearcher(index, keepSearching, numSearches, failures, searching));
      }
      searching.await();

      SearchThreads.shutdown();

      keepSearching.set(false);
      for (Thread searcher : searchers) {
        searcher.join();
      }

      assertEquals(List.of(), failures, "no search failed across the shutdown");
      assertEquals(
          5,
          index.getNearestNeighborRowNums(5, query(0), MetaFilter.empty()).size(),
          "a namespace still serving creates a pool again");
    }
  }

  /**
   * The structures a namespace rations are process-wide and outlive it, so a second namespace in
   * one process must find them as the first left them.
   */
  @Test
  void buildingANamespaceTwiceInOneProcessServesBothTimes() {
    for (int attempt = 0; attempt < 2; ++attempt) {
      try (NearestNeighborSearchIndex index = populatedNamespace()) {
        index.awaitBackgroundTasks();

        SearchResults results = index.getNearestNeighborRowNums(1, query(7), MetaFilter.empty());

        assertEquals(NUM_ROWS, index.size(), "attempt " + attempt);
        assertEquals(7L, results.getRowNum(0), "attempt " + attempt);
      }
    }
  }

  /** Two namespaces at once share those structures rather than each holding their own. */
  @Test
  void twoNamespacesInOneProcessSearchIndependently() {
    try (NearestNeighborSearchIndex first = populatedNamespace();
        NearestNeighborSearchIndex second = populatedNamespace()) {
      second.delete(7);

      assertEquals(7L, first.getNearestNeighborRowNums(1, query(7), MetaFilter.empty()).getRowNum(0));
      assertFalse(
          second.getNearestNeighborRowNums(1, query(7), MetaFilter.empty()).getRowNum(0) == 7L,
          "a delete in one namespace must not reach the other");
      assertEquals(NUM_ROWS, first.size());
      assertEquals(NUM_ROWS - 1, second.size());
    }
  }

  private Thread startSearcher(
      NearestNeighborSearchIndex index,
      AtomicBoolean keepSearching,
      AtomicInteger numSearches,
      List<Throwable> failures,
      CountDownLatch searching) {
    Thread searcher =
        new Thread(
            () -> {
              try {
                do {
                  index.getNearestNeighborRowNums(5, query(0), MetaFilter.empty());
                  numSearches.incrementAndGet();
                  searching.countDown();
                } while (keepSearching.get());
              } catch (RuntimeException e) {
                failures.add(e);
              }
            });
    searcher.setDaemon(true);
    searcher.start();
    return searcher;
  }

  private static NearestNeighborSearchIndex populatedNamespace() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    for (int rowNum = 0; rowNum < NUM_ROWS; ++rowNum) {
      index.insert(vector(rowNum), Map.of("city", rowNum % 2 == 0 ? "sf" : "la"));
    }
    return index;
  }

  /** One coordinate set per row, so each row is nearest to its own query and to nothing else. */
  private static TermsAndValues vector(int rowNum) {
    float[] values = new float[DIMENSION];
    values[rowNum % DIMENSION] = 1.0f + rowNum;
    return new TermsAndValues(new String[0], values);
  }

  private static TermsAndValues query(int rowNum) {
    return vector(rowNum);
  }

  private static NamespaceConfig config() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(DIMENSION)
        .maxCacheSize(64)
        .cacheType("scan")
        // A scan divides its rows into work units and dispatches them to the search pool, which is
        // what a host shutting that pool down or resizing it is acting on. A dense matrix reaches
        // a native library that batches instead, and never submits a work unit at all.
        .indexType("scan")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }
}
