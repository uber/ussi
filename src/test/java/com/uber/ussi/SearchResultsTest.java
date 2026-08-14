package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchResultsTest {

  @Test
  void storesOrderedRowNumsAndSimilarities() {
    SearchResults results = new SearchResults(new long[] {11L, 7L}, new float[] {0.9f, 0.5f});

    assertEquals(2, results.size());
    assertFalse(results.isEmpty());
    assertEquals(11L, results.getRowNum(0));
    assertEquals(0.9f, results.getSimilarity(0));
    assertArrayEquals(new long[] {11L, 7L}, results.getRowNums());
    assertArrayEquals(new float[] {0.9f, 0.5f}, results.getSimilarities());
  }

  @Test
  void defensivelyCopiesArrays() {
    long[] rowNums = {1L};
    float[] similarities = {0.7f};
    SearchResults results = new SearchResults(rowNums, similarities);

    rowNums[0] = 2L;
    similarities[0] = 0.1f;
    results.getRowNums()[0] = 3L;
    results.getSimilarities()[0] = 0.2f;

    assertEquals(1L, results.getRowNum(0));
    assertEquals(0.7f, results.getSimilarity(0));
  }

  @Test
  void emptyResultsAreSupported() {
    SearchResults results = new SearchResults(new long[0], new float[0]);

    assertTrue(results.isEmpty());
    assertEquals(0, results.size());
  }

  @Test
  void constructorRejectsMismatchedArrayLengths() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchResults(new long[] {1L}, new float[] {0.9f, 0.8f}));
  }

  @Test
  void equalityHashCodeAndToStringUseArrayContents() {
    SearchResults results = new SearchResults(new long[] {1L, 2L}, new float[] {0.25f, 0.75f});
    SearchResults same = new SearchResults(new long[] {1L, 2L}, new float[] {0.25f, 0.75f});
    SearchResults differentRows =
        new SearchResults(new long[] {1L, 3L}, new float[] {0.25f, 0.75f});
    SearchResults differentSimilarities =
        new SearchResults(new long[] {1L, 2L}, new float[] {0.25f, 0.5f});

    assertEquals(results, results);
    assertEquals(results, same);
    assertEquals(results.hashCode(), same.hashCode());
    assertNotEquals(results, differentRows);
    assertNotEquals(results, differentSimilarities);
    assertNotEquals(results, "not search results");
    assertEquals("SearchResults{rowNums=[1, 2], similarities=[0.25, 0.75]}", results.toString());
  }
}
