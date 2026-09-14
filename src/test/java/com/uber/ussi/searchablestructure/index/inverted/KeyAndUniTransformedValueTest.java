package com.uber.ussi.searchablestructure.index.inverted;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KeyAndUniTransformedValueTest {

  @Test
  void accessorsReturnTheKeyAndItsContribution() {
    KeyAndUniTransformedValue keyAndValue = new KeyAndUniTransformedValue(7, 3.0);

    assertEquals(7, keyAndValue.getKey());
    assertEquals(3.0, keyAndValue.getUniTransformedValue());
  }
}
