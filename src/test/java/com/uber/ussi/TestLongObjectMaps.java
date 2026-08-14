package com.uber.ussi;

import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TestLongObjectMaps {

  private TestLongObjectMaps() {}

  public static <T> LongObjectHashMap<T> longObjectMap() {
    return new LongObjectHashMap<>();
  }

  public static <T> LongObjectHashMap<T> longObjectMap(long key, T value) {
    LongObjectHashMap<T> map = new LongObjectHashMap<>(1);
    map.put(key, value);
    return map;
  }

  public static <T> LongObjectHashMap<T> longObjectMap(
      long firstKey, T firstValue, long secondKey, T secondValue) {
    LongObjectHashMap<T> map = new LongObjectHashMap<>(2);
    map.put(firstKey, firstValue);
    map.put(secondKey, secondValue);
    return map;
  }

  public static LongHashSet longHashSet(long... values) {
    LongHashSet set = new LongHashSet(values.length);
    for (long value : values) {
      set.add(value);
    }
    return set;
  }

  public static List<Long> sortedKeys(LongObjectHashMap<?> map) {
    return sortedKeys(map.keys().toArray());
  }

  public static List<Long> sortedKeys(LongHashSet set) {
    return sortedKeys(set.toArray());
  }

  public static List<Long> sortedKeys(LongFloatHashMap map) {
    return sortedKeys(map.keys().toArray());
  }

  public static List<Long> rowNums(SearchResults results) {
    return rowNums(results.getRowNums());
  }

  public static List<Long> rowNums(long[] rowNums) {
    List<Long> orderedRowNums = new ArrayList<>(rowNums.length);
    for (long rowNum : rowNums) {
      orderedRowNums.add(rowNum);
    }
    return orderedRowNums;
  }

  private static List<Long> sortedKeys(long[] keys) {
    Arrays.sort(keys);
    List<Long> sortedKeys = new ArrayList<>(keys.length);
    for (long key : keys) {
      sortedKeys.add(key);
    }
    return sortedKeys;
  }
}
