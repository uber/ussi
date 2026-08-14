package com.uber.ussi.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ErrorClassesTest {

  @Test
  void arraysSizeMismatchErrorKeepsMessage() {
    assertEquals("msg", new ArraysSizeMismatchError("msg").getMessage());
  }

  @Test
  void cacheCreationErrorKeepsMessage() {
    assertEquals("msg", new CacheCreationError("msg").getMessage());
  }

  @Test
  void comparatorCreationErrorKeepsMessage() {
    assertEquals("msg", new ComparatorCreationError("msg").getMessage());
  }

  @Test
  void comparatorNormalizerCreationErrorKeepsMessage() {
    assertEquals("msg", new ComparatorNormalizerCreationError("msg").getMessage());
  }

  @Test
  void indexCreationErrorKeepsMessage() {
    assertEquals("msg", new IndexCreationError("msg").getMessage());
  }

  @Test
  void searchResponseErrorKeepsMessage() {
    assertEquals("msg", new SearchResponseError("msg").getMessage());
  }
}
