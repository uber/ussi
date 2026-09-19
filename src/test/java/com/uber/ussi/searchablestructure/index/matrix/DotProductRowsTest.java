package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rising the minimum as the heap fills must change what it costs, never what it keeps. */
class DotProductRowsTest {

  @Test
  void keepsTheBestRows() {
    List<Long> kept = addRows(new float[] {1f, 5f, 3f, 4f, 2f}, /* maxResults */ 2);

    assertEquals(List.of(1L, 3L), kept, "the two strongest rows, by row number");
  }

  /**
   * The minimum the heap proves is one step below its weakest row, so a row scoring exactly as
   * well is still offered to the heap rather than skipped, and the heap keeps as many as it is
   * bounded by. The result is what it would be with no minimum at all.
   */
  @Test
  void keepsTheBoundWhenRowsTieWithTheWeakestKept() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> into = ResultHeaps.newTopResults(2);

    DotProductRows.addRows(
        new float[] {1f, 4f, 4f, 4f}, TestMatrixRows.of(4),
        new RowSelection(0, Float.NEGATIVE_INFINITY, 2), into);

    List<Float> kept =
        into.toList().stream().map(RowNumAndSimilarity::getSimilarity).sorted().toList();
    assertEquals(List.of(4f, 4f), kept);
  }

  @Test
  void keepsNothingBelowTheQuerysOwnMinimum() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> into = ResultHeaps.newTopResults(10);
    float[] dotProducts = {1f, 2f, 3f};

    DotProductRows.addRows(
        dotProducts, TestMatrixRows.of(3), new RowSelection(0, /* minSimilarity */ 2.5f, 10),
        into);

    assertEquals(1, into.toList().size());
  }

  private static List<Long> addRows(float[] dotProducts, int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> into = ResultHeaps.newTopResults(maxResults);

    DotProductRows.addRows(
        dotProducts,
        TestMatrixRows.of(dotProducts.length),
        new RowSelection(0, Float.NEGATIVE_INFINITY, maxResults),
        into);

    return into.toList().stream().map(RowNumAndSimilarity::getRowNum).sorted().toList();
  }
}
