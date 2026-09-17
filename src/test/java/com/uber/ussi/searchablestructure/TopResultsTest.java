package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.uber.ussi.utils.BoundedSizeMaxHeap;
import org.junit.jupiter.api.Test;

class TopResultsTest {

  @Test
  void newTopResultsHeapUsesNearestFirstOrdering() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> heap = TopResults.newTopResultsHeap(2);
    heap.add(new RowNumAndSimilarity(1, 0.8f));
    heap.add(new RowNumAndSimilarity(2, 0.5f));
    heap.add(new RowNumAndSimilarity(3, 0.9f));

    assertEquals(2, heap.size());
    assertFalse(heap.toList().stream().anyMatch(result -> result.getRowNum() == 2));
  }

  @Test
  void conservativeMinSimilaritySitsOneStepBelowTheWeakestKeptResult() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> heap = TopResults.newTopResultsHeap(2);
    heap.add(new RowNumAndSimilarity(1, 0.8f));
    heap.add(new RowNumAndSimilarity(2, 0.5f));

    assertEquals(Math.nextDown(0.5f), TopResults.getConservativeMinSimilarity(heap));
  }

  @Test
  void raisesTheMinimumSimilarityOnlyOnceTheHeapIsFull() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = TopResults.newTopResultsHeap(2);

    assertEquals(
        0.3f,
        TopResults.tightenedMinSimilarity(rows, 0.3f),
        "an empty heap has proved nothing");

    rows.add(new RowNumAndSimilarity(1, 0.9f));
    assertEquals(
        0.3f,
        TopResults.tightenedMinSimilarity(rows, 0.3f),
        "a heap short of the rows the search keeps has proved nothing");

    rows.add(new RowNumAndSimilarity(2, 0.8f));
    assertEquals(
        Math.nextDown(0.8f),
        TopResults.tightenedMinSimilarity(rows, 0.3f),
        "a full heap proves its weakest kept score");

    rows.add(new RowNumAndSimilarity(3, 0.95f));
    assertEquals(
        Math.nextDown(0.9f),
        TopResults.tightenedMinSimilarity(rows, 0.3f),
        "and proves more as its weakest kept score rises");

    assertEquals(
        0.99f,
        TopResults.tightenedMinSimilarity(rows, 0.99f),
        "the search's own minimum stands when it is the higher of the two");
  }
}
