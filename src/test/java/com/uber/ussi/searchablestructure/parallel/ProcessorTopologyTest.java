package com.uber.ussi.searchablestructure.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProcessorTopologyTest {

  @Test
  void namesTheProcessorsAListHolds() {
    List<List<Object>> listsAndProcessorNums =
        List.of(
            List.of("0", Set.of(0)),
            List.of("0,48", Set.of(0, 48)),
            List.of("0-1", Set.of(0, 1)),
            List.of("0-3,8", Set.of(0, 1, 2, 3, 8)),
            List.of("0-23,48-71", rangeOf(0, 23, 48, 71)),
            List.of("0, 2, 4", Set.of(0, 2, 4)),
            List.of("", Set.of()),
            // A range naming one processor, and one naming none.
            List.of("7-7", Set.of(7)),
            List.of("7-6", Set.of()),
            // A processor named twice is one processor.
            List.of("3,3-3", Set.of(3)));
    for (List<Object> testCase : listsAndProcessorNums) {
      String processorList = (String) testCase.get(0);

      assertEquals(
          new TreeSet<>((Set<?>) testCase.get(1)),
          ProcessorTopology.getProcessorNumsInList(processorList),
          processorList);
    }
  }

  /** The processors of two inclusive ranges, as a socket's processor list names them. */
  private static Set<Integer> rangeOf(int first, int last, int alsoFirst, int alsoLast) {
    Set<Integer> processorNums = new TreeSet<>();
    for (int processorNum = first; processorNum <= last; processorNum++) {
      processorNums.add(processorNum);
    }
    for (int processorNum = alsoFirst; processorNum <= alsoLast; processorNum++) {
      processorNums.add(processorNum);
    }
    return processorNums;
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
