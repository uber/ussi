package com.uber.ussi.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedSizeMaxHeapTest {
  private static final Comparator<Integer> INTEGER_ORDER = Comparator.naturalOrder();

  @Test
  void constructorRejectsNegativeMaxSize() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedSizeMaxHeap<>(-1, INTEGER_ORDER));
  }

  @Test
  void addKeepsGreatestElements() {
    BoundedSizeMaxHeap<Integer> heap = new BoundedSizeMaxHeap<>(2, INTEGER_ORDER);

    heap.addAll(new Integer[] {1, 3, 2, 5, 4});

    assertEquals(List.of(4, 5), sortedValues(heap));
    assertTrue(heap.isFull());
  }

  @Test
  void iterableAddAllKeepsGreatestElements() {
    BoundedSizeMaxHeap<Integer> heap = new BoundedSizeMaxHeap<>(2, INTEGER_ORDER);

    heap.addAll(List.of(1, 3, 2, 5, 4));

    assertEquals(List.of(4, 5), sortedValues(heap));
  }

  @Test
  void maxSizeZeroKeepsNoElements() {
    BoundedSizeMaxHeap<Integer> heap = new BoundedSizeMaxHeap<>(0, INTEGER_ORDER);

    heap.add(1);

    assertEquals(0, heap.size());
    assertTrue(heap.isFull());
    assertNull(heap.peek());
    assertEquals(0, heap.toArray(new Integer[0]).length);
  }

  @Test
  void peekReturnsLeastRetainedElement() {
    BoundedSizeMaxHeap<Integer> heap = new BoundedSizeMaxHeap<>(3, INTEGER_ORDER);

    heap.addAll(new Integer[] {10, 20, 30});
    assertEquals(10, heap.peek());

    heap.add(5);
    assertEquals(10, heap.peek());

    heap.add(40);
    assertEquals(20, heap.peek());
  }

  @Test
  void toListReturnsRetainedElements() {
    BoundedSizeMaxHeap<Integer> heap = new BoundedSizeMaxHeap<>(2, INTEGER_ORDER);

    heap.addAll(new Integer[] {1, 3, 2});

    assertEquals(List.of(2, 3), heap.toSortedList(INTEGER_ORDER));
    assertEquals(List.of(2, 3), heap.toList().stream().sorted().toList());
  }

  private static List<Integer> sortedValues(BoundedSizeMaxHeap<Integer> heap) {
    return Arrays.stream(heap.toArray(new Integer[0])).sorted().toList();
  }
}
