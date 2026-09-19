/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerType;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.cache.Cache;
import com.uber.ussi.searchablestructure.cache.CacheFactory;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests the floor the engine carries from one searchable structure to the next.
 *
 * <p>The floor changes only how much work a search does, never what it returns, so these tests pin
 * the rule that derives it and the promise that a structure honours it. That the floor pays for
 * itself is a question for measurement, not for a test.
 */
public final class CrossStructureFloorTest {

  private static final int TERMS_PER_RECORD = 4;

  @Test
  public void raisesTheFloorOnlyOnceEnoughRowsAreHeld() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = heapOf(3, 0.9f, 0.5f);

    // Two of three results held, so nothing is known about what the answer will need.
    assertEquals(
        0.2f, NearestNeighborSearchIndex.tightenedFloor(rows, 0.2f), "floor moved while not full");

    rows.add(new RowNumAndSimilarity(3, 0.7f));

    // Full, so the weakest held result becomes the floor, one step below it so a tie still counts.
    assertEquals(
        Math.nextDown(0.5f), NearestNeighborSearchIndex.tightenedFloor(rows, 0.2f), 0.0f);
  }

  @Test
  public void neverLowersTheFloorTheCallerAskedFor() {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = heapOf(2, 0.9f, 0.5f);

    assertEquals(0.8f, NearestNeighborSearchIndex.tightenedFloor(rows, 0.8f), 0.0f);
  }

  @Test
  public void aStructureReturnsNoRowBeneathTheFloorItWasGiven() {
    Cache cache = scanCache();
    for (int rowNum = 0; rowNum < 200; rowNum++) {
      cache.insert(record(rowNum), Map.of());
    }
    LongTermsAndValues query = record(7);

    List<RowNumAndSimilarity> unfloored =
        cache.getNearestNeighborRowNums(10, query, MetaFilter.empty());
    assertEquals(10, unfloored.size());
    float floor = 0.5f;

    List<RowNumAndSimilarity> floored =
        cache.getNearestNeighborRowNums(10, query, MetaFilter.empty(), floor);

    for (RowNumAndSimilarity row : floored) {
      assertTrue(
          row.getSimilarity() >= floor,
          "Returned a row scoring " + row.getSimilarity() + " beneath the floor " + floor);
    }
    // Every row the floor admits is still found, so the floor prunes work rather than answers.
    assertEquals(
        unfloored.stream().filter(row -> row.getSimilarity() >= floor).toList().size(),
        floored.size());
  }

  @Test
  public void searchingWithoutAFloorSearchesWithNoFloor() {
    Cache cache = scanCache();
    for (int rowNum = 0; rowNum < 50; rowNum++) {
      cache.insert(record(rowNum), Map.of());
    }
    LongTermsAndValues query = record(7);

    assertEquals(
        cache.getNearestNeighborRowNums(10, query, MetaFilter.empty(), 0.0f),
        cache.getNearestNeighborRowNums(10, query, MetaFilter.empty()));
  }

  @Test
  public void returnsTheSameNearestRowsAcrossSeveralStructuresAsOneStructureWould() {
    Random random = new Random(11);
    List<TermsAndValues> inserted = new ArrayList<>();
    for (int row = 0; row < 1_000; row++) {
      inserted.add(sparseRecord(random));
    }
    TermsAndValues query = sparseRecord(random);

    // One engine graduates its rows into several structures, so its searches carry a floor from
    // each structure to the next. The other holds everything in one structure, so no floor is ever
    // carried. The floor may only skip rows that could not have been returned, so they must agree.
    try (NearestNeighborSearchIndex severalStructures =
            NearestNeighborSearchIndex.create(config(/* maxCacheSize */ 200));
        NearestNeighborSearchIndex oneStructure =
            NearestNeighborSearchIndex.create(config(/* maxCacheSize */ 1_000_000))) {
      for (TermsAndValues record : inserted) {
        severalStructures.insert(record, Map.of());
        oneStructure.insert(record, Map.of());
      }
      severalStructures.awaitBackgroundTasks();
      assertTrue(severalStructures.size() == oneStructure.size());

      List<Float> withFloors =
          similarities(severalStructures.getNearestNeighborRowNums(10, query, MetaFilter.empty()));
      List<Float> withoutFloors =
          similarities(oneStructure.getNearestNeighborRowNums(10, query, MetaFilter.empty()));

      assertEquals(10, withFloors.size());
      assertEquals(withoutFloors, withFloors);
    }
  }

  /** The similarities a search returned, strongest first. */
  private static List<Float> similarities(SearchResults results) {
    List<Float> similarities = new ArrayList<>(results.size());
    for (int index = 0; index < results.size(); index++) {
      similarities.add(results.getSimilarity(index));
    }
    return similarities.stream().sorted((left, right) -> Float.compare(right, left)).toList();
  }

  private static List<Float> similarities(List<RowNumAndSimilarity> rows) {
    return rows.stream()
        .map(RowNumAndSimilarity::getSimilarity)
        .sorted((left, right) -> Float.compare(right, left))
        .toList();
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> heapOf(
      int maxResults, float... similarities) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    for (int i = 0; i < similarities.length; i++) {
      rows.add(new RowNumAndSimilarity(i, similarities[i]));
    }
    return rows;
  }

  private static Cache scanCache() {
    return CacheFactory.createCache(config(/* maxCacheSize */ 100_000));
  }

  /** Records overlapping by however far apart their seeds are, so their similarities differ. */
  private static LongTermsAndValues record(int seed) {
    long[] terms = new long[TERMS_PER_RECORD];
    float[] values = new float[TERMS_PER_RECORD];
    for (int i = 0; i < TERMS_PER_RECORD; i++) {
      terms[i] = seed + i;
      values[i] = 1.0f;
    }
    Comparator comparator = ComparatorFactory.createComparator(config(/* maxCacheSize */ 100_000));
    return LongTermsAndValuesTestFactory.create(
        terms, values, comparator.computeUniValue(terms, values));
  }

  private static TermsAndValues sparseRecord(Random random) {
    String[] terms = new String[TERMS_PER_RECORD];
    float[] values = new float[TERMS_PER_RECORD];
    for (int i = 0; i < TERMS_PER_RECORD; i++) {
      terms[i] = Integer.toString(random.nextInt(40));
      values[i] = 1.0f;
    }
    return new TermsAndValues(terms, values);
  }

  private static NamespaceConfig config(int maxCacheSize) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(TERMS_PER_RECORD)
        .maxCacheSize(maxCacheSize)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType("jaccard")
        .comparatorNormalizerType(ComparatorNormalizerType.IDENTITY.getParamValue())
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }
}
