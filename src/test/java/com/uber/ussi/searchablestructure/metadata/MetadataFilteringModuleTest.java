package com.uber.ussi.searchablestructure.metadata;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static com.uber.ussi.TestLongObjectMaps.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataFilteringModuleTest {

  @Test
  void constructorSeedsRowsFromMetadataMap() {
    MetadataFilteringModule module =
        new MetadataFilteringModule(longObjectMap(1, longMeta(Map.of("city", "sf"))));

    assertEquals(1, module.size());
    assertTrue(module.doesMatch(1, new MetaFilter(Map.of("city", List.of("sf")))));
  }

  @Test
  void constructorAcceptsNullMetadataMap() {
    MetadataFilteringModule module = new MetadataFilteringModule(null);

    assertTrue(module.isEmpty());
  }

  @Test
  void putRejectsDuplicateRowNum() {
    MetadataFilteringModule module = new MetadataFilteringModule();

    assertTrue(module.put(1, LongMeta.empty()));
    assertFalse(module.put(1, LongMeta.empty()));
  }

  @Test
  void putRejectsMetadataDictionaryKeyCollision() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, new CollidingLongMeta(1));

    assertThrows(IllegalStateException.class, () -> module.put(2, new CollidingLongMeta(2)));
  }

  @Test
  void doesMatchUsesOrWithinKeyAndAndAcrossKeys() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, longMeta(Map.of("city", "sf", "country", "us")));
    module.put(2, longMeta(Map.of("city", "la", "country", "us")));
    module.put(3, longMeta(Map.of("city", "sf", "country", "fr")));

    MetaFilter filter =
        new MetaFilter(Map.of("city", List.of("sf", "la"), "country", List.of("us")));

    assertTrue(module.doesMatch(1, filter));
    assertTrue(module.doesMatch(2, filter));
    assertFalse(module.doesMatch(3, filter));
  }

  @Test
  void getMatchingRowNumsScansMetadataDictionary() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, longMeta(Map.of("city", "sf", "country", "us")));
    module.put(2, longMeta(Map.of("city", "la", "country", "us")));
    module.put(3, longMeta(Map.of("city", "ny", "country", "us")));
    module.put(4, longMeta(Map.of("city", "sf", "country", "fr")));

    MetaFilter filter =
        new MetaFilter(Map.of("city", List.of("sf", "la"), "country", List.of("us")));

    assertEquals(List.of(1L, 2L), sortedKeys(module.getMatchingRowNums(filter)));
    assertEquals(2, module.getNumRowsMatching(filter));
  }

  @Test
  void rowsWithSameMetadataShareMetadataDictionaryEntry() {
    MetadataFilteringModule module = new MetadataFilteringModule();

    module.put(1, longMeta(Map.of("city", "sf", "country", "us")));
    module.put(2, longMeta(Map.of("country", "us", "city", "sf")));
    module.put(3, longMeta(Map.of("city", "la", "country", "us")));

    assertEquals(2, module.getNumMetadataDictionaryEntries());

    assertTrue(module.delete(1));
    assertEquals(2, module.getNumMetadataDictionaryEntries());

    assertTrue(module.delete(2));
    assertEquals(1, module.getNumMetadataDictionaryEntries());
  }

  @Test
  void deleteRemovesRowsFromMetadataDictionary() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, longMeta(Map.of("city", "sf")));
    module.put(2, longMeta(Map.of("city", "sf")));

    assertTrue(module.delete(1));
    assertFalse(module.delete(1));

    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf")));
    assertFalse(module.doesMatch(1, filter));
    assertTrue(module.doesMatch(2, filter));
    assertEquals(List.of(2L), sortedKeys(module.getMatchingRowNums(filter)));
    assertFalse(module.getAllMetadata().containsKey(1));
  }

  @Test
  void emptyFilterMatchesAllRowsKnownToModule() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, LongMeta.empty());
    module.put(2, longMeta(Map.of("city", "sf")));

    assertTrue(module.doesMatch(1, MetaFilter.empty()));
    assertFalse(module.doesMatch(99, MetaFilter.empty()));
    assertEquals(List.of(1L, 2L), sortedKeys(module.getMatchingRowNums(MetaFilter.empty())));
    assertEquals(2, module.getNumRowsMatching(MetaFilter.empty()));
  }

  @Test
  void emptyFilterPreFilteringHonorsLimit() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, LongMeta.empty());
    module.put(2, LongMeta.empty());

    assertFalse(module.getMatchingRowNumsIfUnderLimit(MetaFilter.empty(), 1).isSuccess());
    assertEquals(
        List.of(1L, 2L), sortedKeys(module.getMatchingRowNumsIfUnderLimit(null, 2).getRowNums()));
  }

  @Test
  void getMatchingRowNumsIfUnderLimitReturnsSuccessOnlyWhenSmallEnough() {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, longMeta(Map.of("city", "sf")));
    module.put(2, longMeta(Map.of("city", "sf")));
    module.put(3, longMeta(Map.of("city", "la")));

    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf")));

    PreFilteringResult success = module.getMatchingRowNumsIfUnderLimit(filter, 2);
    PreFilteringResult failure = module.getMatchingRowNumsIfUnderLimit(filter, 1);

    assertTrue(success.isSuccess());
    assertEquals(List.of(1L, 2L), sortedKeys(success.getRowNums()));
    assertFalse(failure.isSuccess());
    assertTrue(failure.getRowNums().isEmpty());
  }

  @Test
  void getMatchingRowNumsIfUnderLimitRejectsNegativeLimit() {
    MetadataFilteringModule module = new MetadataFilteringModule();

    assertThrows(
        IllegalArgumentException.class,
        () -> module.getMatchingRowNumsIfUnderLimit(MetaFilter.empty(), -1));
  }

  @Test
  void deleteReturnsFalseWhenDictionaryEntryIsMissing() throws ReflectiveOperationException {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, LongMeta.empty());
    metadataDictionary(module).clear();

    assertFalse(module.delete(1));
  }

  @Test
  void getAllMetadataSkipsRowsWithMissingDictionaryEntry() throws ReflectiveOperationException {
    MetadataFilteringModule module = new MetadataFilteringModule();
    module.put(1, LongMeta.empty());
    metadataDictionary(module).clear();

    assertTrue(module.getAllMetadata().isEmpty());
  }

  @SuppressWarnings("unchecked")
  private static LongObjectHashMap<MetadataDictionaryEntry> metadataDictionary(
      MetadataFilteringModule module) throws ReflectiveOperationException {
    Field field = MetadataFilteringModule.class.getDeclaredField("metadataDictionary");
    field.setAccessible(true);
    return (LongObjectHashMap<MetadataDictionaryEntry>) field.get(module);
  }

  private static LongMeta longMeta(Map<String, String> metadata) {
    return new LongMeta(metadata, /* requireLongKeysAndValues */ false);
  }

  private static final class CollidingLongMeta extends LongMeta {
    private final int id;

    private CollidingLongMeta(int id) {
      this.id = id;
    }

    @Override
    public long longHashCode() {
      return 42L;
    }

    @Override
    public boolean equals(Object object) {
      return object instanceof CollidingLongMeta && id == ((CollidingLongMeta) object).id;
    }

    @Override
    public int hashCode() {
      return id;
    }
  }
}
