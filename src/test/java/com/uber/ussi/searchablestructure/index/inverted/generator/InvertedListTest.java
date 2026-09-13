package com.uber.ussi.searchablestructure.index.inverted.generator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class InvertedListTest {

  @Test
  void exposesRowNumsValuesAndSize() {
    InvertedList invertedList = new InvertedList(new long[] {3, 1}, new float[] {0.5f, 1.0f});

    assertArrayEquals(new long[] {3, 1}, invertedList.getRowNums());
    assertArrayEquals(new float[] {0.5f, 1.0f}, invertedList.getValues());
    assertEquals(2, invertedList.size());
  }

  @Test
  void equalsAndHashCodeConsiderBothRowNumsAndValues() {
    InvertedList left = new InvertedList(new long[] {1, 2}, new float[] {1.0f, 2.0f});
    InvertedList same = new InvertedList(new long[] {1, 2}, new float[] {1.0f, 2.0f});
    InvertedList differentRows = new InvertedList(new long[] {1, 3}, new float[] {1.0f, 2.0f});
    InvertedList differentValues = new InvertedList(new long[] {1, 2}, new float[] {1.0f, 3.0f});

    assertEquals(left, same);
    assertEquals(left.hashCode(), same.hashCode());
    assertNotEquals(left, differentRows);
    assertNotEquals(left, differentValues);
  }
}
