package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class SearchFanOutTest {

  @Test
  void keepsTheBestRowsAcrossPartsHoweverManyRunAtOnce() {
    int[][] partsAndAtOnce = {
      {1, 1}, {1, 8}, {2, 1}, {2, 2}, {4, 1}, {4, 2}, {4, 4}, {4, 8},
      {7, 3}, {16, 1}, {16, 5}, {16, 16}, {48, 6}, {48, 48},
    };
    for (int[] testCase : partsAndAtOnce) {
      int numParts = testCase[0];
      int atOnce = testCase[1];
      String message = "numParts=" + numParts + " atOnce=" + atOnce;
      for (int maxResults : new int[] {1, 3, 10}) {
        List<RowNumAndSimilarity> kept =
            SearchFanOut.inWaves(
                numParts, atOnce, maxResults, part -> rowsOfPart(part, numParts));
        assertEquals(
            bestOfAllParts(numParts, maxResults),
            scoresOf(kept),
            message + " maxResults=" + maxResults);
      }
    }
  }

  @Test
  void searchesEveryPartExactlyOnce() {
    int[][] partsAndAtOnce = {{4, 2}, {16, 5}, {16, 1}, {48, 6}, {9, 9}};
    for (int[] testCase : partsAndAtOnce) {
      int numParts = testCase[0];
      int atOnce = testCase[1];
      AtomicIntegerArray searches = new AtomicIntegerArray(numParts);

      SearchFanOut.inWaves(
          numParts,
          atOnce,
          5,
          part -> {
            searches.incrementAndGet(part);
            return rowsOfPart(part, numParts);
          });

      for (int part = 0; part < numParts; part++) {
        assertEquals(
            1, searches.get(part), "numParts=" + numParts + " atOnce=" + atOnce + " part=" + part);
      }
    }
  }

  @Test
  void holdsNothingWhenThereIsNoPartToSearch() {
    assertTrue(SearchFanOut.inWaves(0, 4, 10, part -> rowsOfPart(part, 1)).isEmpty());
  }

  @Test
  void throwsWhatAPartThrew() {
    for (int atOnce : new int[] {1, 2, 8}) {
      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () ->
                  SearchFanOut.inWaves(
                      8,
                      atOnce,
                      5,
                      part -> {
                        if (part == 5) {
                          throw new IllegalStateException("part five could not be searched");
                        }
                        return rowsOfPart(part, 8);
                      }));
      assertEquals("part five could not be searched", thrown.getMessage(), "atOnce=" + atOnce);
    }
  }

  /** Rows of one part, scored so that no two parts hold a row scoring the same. */
  private static List<RowNumAndSimilarity> rowsOfPart(int part, int numParts) {
    List<RowNumAndSimilarity> rows = new ArrayList<>();
    for (int row = 0; row < 5; row++) {
      long rowNum = (long) row * numParts + part;
      rows.add(new RowNumAndSimilarity(rowNum, rowNum / 1000.0f));
    }
    return rows;
  }

  private static List<Float> bestOfAllParts(int numParts, int maxResults) {
    List<Float> scores = new ArrayList<>();
    for (int part = 0; part < numParts; part++) {
      for (RowNumAndSimilarity row : rowsOfPart(part, numParts)) {
        scores.add(row.getSimilarity());
      }
    }
    scores.sort(java.util.Comparator.<Float>naturalOrder().reversed());
    return scores.subList(0, Math.min(maxResults, scores.size()));
  }

  private static List<Float> scoresOf(List<RowNumAndSimilarity> rows) {
    List<Float> scores = new ArrayList<>();
    for (RowNumAndSimilarity row : rows) {
      scores.add(row.getSimilarity());
    }
    scores.sort(java.util.Comparator.<Float>naturalOrder().reversed());
    return scores;
  }
}
