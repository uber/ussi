package com.uber.ussi.searchablestructure.index.inverted;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RowNumAndUniValueTest {

  private static final OrderingCase[] ORDERING_CASES = {
    new OrderingCase(
        new RowNumAndUniValue[] {
          new RowNumAndUniValue(2, 2.0),
          new RowNumAndUniValue(1, 1.0),
          new RowNumAndUniValue(3, 2.0)
        },
        new long[] {1, 2, 3}),
    new OrderingCase(
        new RowNumAndUniValue[] {
          new RowNumAndUniValue(5, 1.0, 0.25f), new RowNumAndUniValue(2, 1.0, 0.75f)
        },
        new long[] {2, 5}),
  };

  @Test
  void sortingOrdersByUniValueThenRowNum() {
    for (OrderingCase testCase : ORDERING_CASES) {
      RowNumAndUniValue[] entries = testCase.entries.clone();
      Arrays.sort(entries);
      long[] rowNums = new long[entries.length];
      for (int index = 0; index < entries.length; ++index) {
        rowNums[index] = entries[index].getRowNum();
      }
      assertArrayEquals(testCase.expectedRowOrder, rowNums, testCase.label());
    }
  }

  @Test
  void valueTravelsWithTheRowThroughSorting() {
    RowNumAndUniValue[] entries = {
      new RowNumAndUniValue(2, 2.0, 0.25f), new RowNumAndUniValue(1, 1.0, 0.75f)
    };
    Arrays.sort(entries);
    assertEquals(0.75f, entries[0].getValue());
    assertEquals(0.25f, entries[1].getValue());
  }

  private record OrderingCase(RowNumAndUniValue[] entries, long[] expectedRowOrder) {
    String label() {
      return Arrays.toString(expectedRowOrder);
    }
  }
}
