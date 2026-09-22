/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MemoryFootprintTest {

  @Test
  void reportsTheBytesItWasGivenSeparately() {
    long[][] onHeapNativeAndExpected = {
      // A namespace holding nothing reports nothing.
      {0, 0},
      // A scorer reading the chunks holds no native memory.
      {4096, 0},
      // A scorer holding its own copy holds both.
      {4096, 8192},
      // The two are reported apart rather than summed, so neither masks the other.
      {0, 8192},
      // Counts beyond a 32-bit range, which a namespace of two million dense rows reaches.
      {4_100_000_000L, 4_100_000_000L},
    };
    for (long[] testCase : onHeapNativeAndExpected) {
      MemoryFootprint footprint = new MemoryFootprint(testCase[0], testCase[1]);

      assertEquals(testCase[0], footprint.getOnHeapBytes(), "on-heap bytes");
      assertEquals(testCase[1], footprint.getNativeBytes(), "native bytes");
    }
  }
}
