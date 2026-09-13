package com.uber.ussi.searchablestructure.inverted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class KeyAndPrefixFilteringDataTest {

  @Test
  void sortingOrdersByNumRowsThenByDescendingContribution() {
    KeyAndPrefixFilteringData lowFrequency = new KeyAndPrefixFilteringData(2, 1, 1.0);
    KeyAndPrefixFilteringData highContribution = new KeyAndPrefixFilteringData(3, 2, 2.0);
    KeyAndPrefixFilteringData lowContribution = new KeyAndPrefixFilteringData(1, 2, 1.0);
    KeyAndPrefixFilteringData[] data = {lowContribution, highContribution, lowFrequency};

    Arrays.sort(data);

    assertSame(lowFrequency, data[0]);
    assertSame(highContribution, data[1]);
    assertSame(lowContribution, data[2]);
  }

  @Test
  void accessorsReturnTheKeyItsRowCountAndItsContribution() {
    KeyAndPrefixFilteringData data = new KeyAndPrefixFilteringData(2, 1, 1.0);

    assertEquals(2, data.getSparseKey());
    assertEquals(1, data.getNumRows());
    assertEquals(1.0, data.getUniTransformedValue());
  }
}
