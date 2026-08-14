package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowNumAndSimilarityTest {

  private static final float DELTA = 1e-6f;

  @Test
  void accessorsReturnConstructorValues() {
    RowNumAndSimilarity hit = new RowNumAndSimilarity(7, 0.5f);

    assertEquals(7, hit.getRowNum());
    assertEquals(0.5f, hit.getSimilarity(), DELTA);
  }

  @Test
  void equalsAndHashCodeUseRowNumAndSimilarity() {
    RowNumAndSimilarity first = new RowNumAndSimilarity(7, 0.5f);
    RowNumAndSimilarity second = new RowNumAndSimilarity(7, 0.5f);
    RowNumAndSimilarity different = new RowNumAndSimilarity(8, 0.5f);

    assertEquals(first, second);
    assertEquals(first, first);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, different);
    assertNotEquals(first, "not-a-hit");
  }

  @Test
  void toStringIncludesFields() {
    assertTrue(new RowNumAndSimilarity(7, 0.5f).toString().contains("RowNumAndSimilarity{"));
  }

  @Test
  void nearestFirstComparatorSortsBySimilarityThenRowNum() {
    List<RowNumAndSimilarity> hits = new ArrayList<>();
    hits.add(new RowNumAndSimilarity(9, 0.5f));
    hits.add(new RowNumAndSimilarity(2, 0.5f));
    hits.add(new RowNumAndSimilarity(1, 0.9f));

    hits.sort(RowNumAndSimilarity.NEAREST_FIRST);

    assertEquals(List.of(1L, 2L, 9L), hits.stream().map(RowNumAndSimilarity::getRowNum).toList());
  }

  @Test
  void topResultsHeapOrderTreatsSmallerRowNumAsBetterTieBreaker() {
    List<RowNumAndSimilarity> hits = new ArrayList<>();
    hits.add(new RowNumAndSimilarity(2, 0.5f));
    hits.add(new RowNumAndSimilarity(9, 0.5f));
    hits.add(new RowNumAndSimilarity(1, 0.9f));

    hits.sort(RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);

    assertEquals(List.of(9L, 2L, 1L), hits.stream().map(RowNumAndSimilarity::getRowNum).toList());
  }
}
