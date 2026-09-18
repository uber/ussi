/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The cores of one processor socket, out of the processors this process may run on.
 *
 * <p>The processors a machine reports are more than the parallelism one piece of work can convert.
 * Two hardware threads of a core share that core's execution units, and a socket reaches the memory
 * attached to another socket over a link. So the cores of one socket bound the threads a single
 * piece of work gains from.
 *
 * <p>Sockets rather than memory nodes, because firmware settings divide one socket into several
 * memory nodes on some machines, which changes the memory node count while the cores and the links
 * between them stay as they are.
 *
 * <p>A machine whose kernel reports neither figure is one socket of single-threaded cores, which is
 * what its processor count already states.
 */
final class ProcessorTopology {

  private static final Path PROCESSORS = Path.of("/sys/devices/system/cpu");
  private static final Path THREAD_SIBLINGS =
      Path.of("/sys/devices/system/cpu/cpu0/topology/thread_siblings_list");

  private ProcessorTopology() {}

  /**
   * The cores of one socket. Never fewer than one, and never more than the processors this process
   * may run on.
   */
  static int getNumCoresPerSocket() {
    int numProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
    int numCoresPerSocket = numProcessors / (getNumSockets() * getNumThreadsPerCore());
    return Math.max(1, Math.min(numProcessors, numCoresPerSocket));
  }

  /** The sockets the kernel names a processor of, or one where it names none. */
  private static int getNumSockets() {
    Set<String> socketIds = new HashSet<>();
    try (Stream<Path> processors = Files.list(PROCESSORS)) {
      for (Path processor : processors.toList()) {
        if (!processor.getFileName().toString().matches("cpu\\d+")) {
          continue;
        }
        Path socketId = processor.resolve("topology/physical_package_id");
        if (Files.isReadable(socketId)) {
          socketIds.add(Files.readString(socketId).trim());
        }
      }
    } catch (IOException | RuntimeException e) {
      return 1;
    }
    return Math.max(1, socketIds.size());
  }

  /** The hardware threads one core carries, or one where the kernel names none. */
  private static int getNumThreadsPerCore() {
    try {
      return Math.max(1, getNumProcessorsInList(Files.readString(THREAD_SIBLINGS).trim()));
    } catch (IOException | RuntimeException e) {
      return 1;
    }
  }

  /**
   * The processors named by a kernel processor list, whose parts are either single numbers or
   * inclusive ranges, as in {@code 0,48} and {@code 0-23}.
   */
  static int getNumProcessorsInList(String processorList) {
    int numProcessors = 0;
    for (String part : processorList.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int dash = trimmed.indexOf('-');
      if (dash < 0) {
        numProcessors++;
        continue;
      }
      int first = Integer.parseInt(trimmed.substring(0, dash));
      int last = Integer.parseInt(trimmed.substring(dash + 1));
      numProcessors += Math.max(0, last - first + 1);
    }
    return numProcessors;
  }
}
