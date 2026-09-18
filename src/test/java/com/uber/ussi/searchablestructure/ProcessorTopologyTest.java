package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProcessorTopologyTest {

  @Test
  void countsTheProcessorsAListNames() {
    String[][] listsAndNumProcessors = {
      {"0", "1"},
      {"0,48", "2"},
      {"0-1", "2"},
      {"0-23", "24"},
      {"0-23,48-71", "48"},
      {"0, 2, 4", "3"},
      {"", "0"},
      // A range naming one processor, and one naming none.
      {"7-7", "1"},
      {"7-6", "0"},
    };
    for (String[] testCase : listsAndNumProcessors) {
      assertEquals(
          Integer.parseInt(testCase[1]),
          ProcessorTopology.getNumProcessorsInList(testCase[0]),
          testCase[0]);
    }
  }

  @Test
  void holdsAtLeastOneCoreAndNoMoreThanTheProcessors() {
    int numCoresPerSocket = ProcessorTopology.getNumCoresPerSocket();

    assertTrue(numCoresPerSocket >= 1, "a socket of " + numCoresPerSocket + " cores");
    assertTrue(
        numCoresPerSocket <= Math.max(1, Runtime.getRuntime().availableProcessors()),
        "a socket of " + numCoresPerSocket + " cores, more than the processors");
  }
}
