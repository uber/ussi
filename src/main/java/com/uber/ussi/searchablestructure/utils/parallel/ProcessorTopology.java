/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The cores of one processor socket, and no more of them than this process may run on.
 *
 * <p>Two hardware threads of one core share that core's execution units, and a socket reaches the
 * memory attached to another socket over a link. The cores of one socket are the threads one work
 * unit gains from.
 *
 * <p>A socket's cores are counted from the whole machine, and the processors this process may run
 * on bound the result rather than dividing it. A process is held to a share of a machine by a
 * processor set, by a bandwidth quota, or by both, and each of those leaves the sockets and the
 * hardware threads of the machine as they are while lowering the processors the process may use.
 *
 * <p>A machine whose kernel names no processor is taken to carry every processor this process may
 * run on in one socket of single-threaded cores.
 *
 * <p>Two machines are described loosely. A socket divided into several cache domains holds cores
 * that reach one another's memory at differing cost, and all of them are counted as one socket's.
 * A process pinned to both hardware threads of one core runs on two processors of one core, and
 * two is what the processors bounding this count come to.
 */
final class ProcessorTopology {

  private static final Path PROCESSORS = Path.of("/sys/devices/system/cpu");

  private ProcessorTopology() {}

  /**
   * The cores of one socket. Never fewer than one, and never more than the processors this process
   * may run on.
   */
  static int getNumCoresPerSocket() {
    int numProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
    Set<Integer> machineProcessorNums = getMachineProcessorNums();
    if (machineProcessorNums.isEmpty()) {
      return numProcessors;
    }
    int numCoresPerSocket =
        machineProcessorNums.size()
            / (getNumSockets(machineProcessorNums)
                * getMaxNumThreadsPerCore(machineProcessorNums));
    return Math.max(1, Math.min(numProcessors, numCoresPerSocket));
  }

  /** Every processor the kernel holds a folder for, which is every processor of the machine. */
  private static Set<Integer> getMachineProcessorNums() {
    Set<Integer> processorNums = new TreeSet<>();
    try (Stream<Path> processors = Files.list(PROCESSORS)) {
      for (Path processor : processors.toList()) {
        String name = processor.getFileName().toString();
        if (name.matches("cpu\\d+")) {
          processorNums.add(Integer.parseInt(name.substring("cpu".length())));
        }
      }
    } catch (IOException | RuntimeException e) {
      return processorNums;
    }
    return processorNums;
  }

  /** The sockets the given processors belong to. */
  private static int getNumSockets(Set<Integer> processorNums) {
    Set<String> socketIds = new HashSet<>();
    for (int processorNum : processorNums) {
      String socketId = readTopology(processorNum, "physical_package_id");
      if (socketId != null) {
        socketIds.add(socketId);
      }
    }
    return Math.max(1, socketIds.size());
  }

  /**
   * The most hardware threads any one of the given processors shares a core with, counting itself.
   * The widest core is taken because a machine of cores of differing widths divides its processors
   * into fewer cores than its narrowest core would suggest.
   */
  private static int getMaxNumThreadsPerCore(Set<Integer> processorNums) {
    int maxNumThreadsPerCore = 1;
    for (int processorNum : processorNums) {
      String siblings = readTopology(processorNum, "thread_siblings_list");
      if (siblings == null) {
        continue;
      }
      maxNumThreadsPerCore =
          Math.max(maxNumThreadsPerCore, getProcessorNumsInList(siblings).size());
    }
    return maxNumThreadsPerCore;
  }

  /**
   * What a processor's topology folder holds under {@code name}, or null where it holds nothing.
   */
  private static String readTopology(int processorNum, String name) {
    Path held = PROCESSORS.resolve("cpu" + processorNum).resolve("topology").resolve(name);
    try {
      return Files.isReadable(held) ? Files.readString(held).trim() : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The processors a kernel processor list names. Its elements are either single numbers or
   * inclusive ranges, as in {@code 0,48} and {@code 0-23}.
   */
  static Set<Integer> getProcessorNumsInList(String processorList) {
    Set<Integer> processorNums = new TreeSet<>();
    for (String element : processorList.split(",")) {
      String trimmed = element.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int dash = trimmed.indexOf('-');
      if (dash < 0) {
        processorNums.add(Integer.parseInt(trimmed));
        continue;
      }
      int first = Integer.parseInt(trimmed.substring(0, dash));
      int last = Integer.parseInt(trimmed.substring(dash + 1));
      for (int processorNum = first; processorNum <= last; processorNum++) {
        processorNums.add(processorNum);
      }
    }
    return processorNums;
  }
}
