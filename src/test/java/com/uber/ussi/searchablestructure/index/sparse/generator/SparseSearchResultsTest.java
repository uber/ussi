package com.uber.ussi.searchablestructure.index.sparse.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import org.junit.jupiter.api.Test;

class SparseSearchResultsTest {

  @Test
  void newTopResultsHeapUsesNearestFirstOrdering() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> heap = SparseSearchResults.newTopResultsHeap(2);
    heap.add(new RowNumAndSimilarity(1, 0.8f));
    heap.add(new RowNumAndSimilarity(2, 0.5f));
    heap.add(new RowNumAndSimilarity(3, 0.9f));

    assertEquals(2, heap.size());
    assertFalse(heap.toList().stream().anyMatch(result -> result.getRowNum() == 2));
  }

  @Test
  void conservativeMinSimilaritySitsOneStepBelowTheWeakestKeptResult() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> heap = SparseSearchResults.newTopResultsHeap(2);
    heap.add(new RowNumAndSimilarity(1, 0.8f));
    heap.add(new RowNumAndSimilarity(2, 0.5f));

    assertEquals(Math.nextDown(0.5f), SparseSearchResults.getConservativeMinSimilarity(heap));
  }
}
