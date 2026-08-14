/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Keeps at most {@code maxSize} elements with the greatest comparator values. */
public final class BoundedSizeMaxHeap<T> {
  private final int maxSize;
  private final Comparator<T> comparator;
  /*
   * Elements stay here until the heap is needed, avoiding priority queue cost for small result
   * sets.
   */
  private final ArrayList<T> unsortedCollection;
  private final PriorityQueue<T> priorityQueue;

  public BoundedSizeMaxHeap(int maxSize, Comparator<T> comparator) {
    if (maxSize < 0) {
      throw new IllegalArgumentException("maxSize should be at least 0.");
    }
    this.comparator = comparator;
    this.maxSize = maxSize;
    this.unsortedCollection = new ArrayList<>(maxSize);
    this.priorityQueue = new PriorityQueue<>(comparator);
  }

  public void add(T element) {
    if (maxSize == 0) {
      return;
    }
    if (unsortedCollection.size() == maxSize) {
      transferElements();
    }
    if (priorityQueue.isEmpty()) {
      addToUnsortedCollection(element);
    } else {
      addToPriorityQueue(element);
    }
  }

  public void addAll(T[] elements) {
    for (T element : elements) {
      add(element);
    }
  }

  public void addAll(Iterable<? extends T> elements) {
    for (T element : elements) {
      add(element);
    }
  }

  public T[] toArray(T[] emptyTypeArray) {
    if (priorityQueue.isEmpty()) {
      return unsortedCollection.toArray(emptyTypeArray);
    }
    return priorityQueue.toArray(emptyTypeArray);
  }

  /**
   * Returns the retained elements without sorting them. Use {@link #toSortedList} when order
   * matters.
   */
  public List<T> toList() {
    if (priorityQueue.isEmpty()) {
      return List.copyOf(unsortedCollection);
    }
    return List.copyOf(priorityQueue);
  }

  public List<T> toSortedList(Comparator<? super T> outputOrder) {
    List<T> sortedElements = new ArrayList<>(toList());
    sortedElements.sort(outputOrder);
    return List.copyOf(sortedElements);
  }

  public T peek() {
    if (priorityQueue.isEmpty()) {
      transferElements();
    }
    return priorityQueue.peek();
  }

  public int size() {
    if (priorityQueue.isEmpty()) {
      return unsortedCollection.size();
    }
    return priorityQueue.size();
  }

  public boolean isFull() {
    return size() == maxSize;
  }

  private void addToUnsortedCollection(T element) {
    unsortedCollection.add(element);
  }

  private void addToPriorityQueue(T element) {
    if (priorityQueue.size() == maxSize) {
      if (comparator.compare(element, priorityQueue.peek()) > 0) {
        priorityQueue.poll();
        priorityQueue.add(element);
      }
    } else {
      priorityQueue.add(element);
    }
  }

  private void transferElements() {
    for (T element : unsortedCollection) {
      addToPriorityQueue(element);
    }
    unsortedCollection.clear();
  }
}
