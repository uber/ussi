package com.uber.ussi.searchablestructure.utils.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  /**
   * A machine whose kernel names its processors, which is every machine the reading below was
   * written for. A kernel that names none is the other path, and is what this machine may present.
   */
  @Nested
  class AKernelThatNamesItsProcessors {

    @TempDir Path kernel;

    private Path originalProcessors;

    @BeforeEach
    void readTheKernelUnderTheTemporaryFolder() {
      originalProcessors = ProcessorTopology.processors;
      ProcessorTopology.processors = kernel;
    }

    @AfterEach
    void readTheRealKernelAgain() {
      ProcessorTopology.processors = originalProcessors;
    }

    @Test
    void countsTheCoresOfOneSocketFromWhatTheKernelNames() throws IOException {
      int numProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
      // Eight processors over two sockets of two-threaded cores, which is two cores a socket.
      // Entries the reading must skip: a folder naming no processor, and a processor whose
      // topology the kernel does not describe.
      for (int processorNum = 0; processorNum < 8; ++processorNum) {
        writeProcessor(processorNum, String.valueOf(processorNum / 4), siblingsOf(processorNum));
      }
      Files.createDirectories(kernel.resolve("cpufreq"));
      Files.createDirectories(kernel.resolve("cpu8"));

      int numCoresPerSocket = ProcessorTopology.getNumCoresPerSocket();

      assertEquals(Math.min(numProcessors, 2), numCoresPerSocket);
    }

    /**
     * A kernel that describes no socket and no core leaves one of each, so the processors it names
     * are taken to be the cores of one socket.
     */
    @Test
    void takesUndescribedProcessorsAsTheCoresOfOneSocket() throws IOException {
      int numProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
      for (int processorNum = 0; processorNum < 4; ++processorNum) {
        Files.createDirectories(kernel.resolve("cpu" + processorNum));
      }

      assertEquals(Math.min(numProcessors, 4), ProcessorTopology.getNumCoresPerSocket());
    }

    /** A kernel naming no processor at all leaves the processors the process may run on. */
    @Test
    void fallsBackToTheProcessorsTheProcessMayRunOn() {
      assertEquals(
          Math.max(1, Runtime.getRuntime().availableProcessors()),
          ProcessorTopology.getNumCoresPerSocket());
    }

    /** Both hardware threads of one core name each other, so the core is two processors wide. */
    private String siblingsOf(int processorNum) {
      int firstOfPair = processorNum - (processorNum % 2);
      return firstOfPair + "-" + (firstOfPair + 1);
    }

    private void writeProcessor(int processorNum, String socketId, String threadSiblings)
        throws IOException {
      Path topology = kernel.resolve("cpu" + processorNum).resolve("topology");
      Files.createDirectories(topology);
      Files.writeString(topology.resolve("physical_package_id"), socketId + "\n");
      Files.writeString(topology.resolve("thread_siblings_list"), threadSiblings + "\n");
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
