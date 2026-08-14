package com.uber.ussi.entity.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LongMetaTest {

  @Test
  void numericConstructorParsesKeysAndValues() {
    LongMeta meta = new LongMeta(Map.of("1", "2"), true);

    assertEquals(LongMeta.longHashCode(1L, 2L), meta.getLongHashCode());
  }

  @Test
  void hashedConstructorUsesFingerprints() {
    LongMeta meta = new LongMeta(Map.of("city", "sf"), false);

    assertTrue(meta.getLongHashCode() != 0L);
  }

  @Test
  void longHashCodeOfTwoStringsCombinesFingerprints() {
    assertEquals(
        LongMeta.longHashCode(LongMeta.longHashCode("a"), LongMeta.longHashCode("b")),
        LongMeta.longHashCode("a", "b"));
  }

  @Test
  void intersectsReturnsTrueWhenFilterShareValue() {
    LongMeta meta = new LongMeta(Map.of("1", "2"), true);
    LongHashSet matching = new LongHashSet();
    matching.add(LongMeta.longHashCode(1L, 2L));

    assertTrue(meta.intersects(matching));
  }

  @Test
  void intersectsReturnsFalseWhenNoSharedValue() {
    LongMeta meta = new LongMeta(Map.of("1", "2"), true);
    LongHashSet nonMatching = new LongHashSet();
    nonMatching.add(99L);

    assertFalse(meta.intersects(nonMatching));
  }

  @Test
  void emptyMetaHasNoEntries() {
    assertEquals(0L, LongMeta.empty().getLongHashCode());
  }

  @Test
  void equalsAndHashCodeUseMetadata() {
    LongMeta first = new LongMeta(Map.of("1", "2"), true);
    LongMeta second = new LongMeta(Map.of("1", "2"), true);
    LongMeta different = new LongMeta(Map.of("1", "3"), true);

    assertEquals(first, second);
    assertEquals(first, first);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, different);
    assertNotEquals(first, "not-a-meta");
  }

  @Test
  void swappedKeyAndValueAreNotEqual() {
    LongMeta keyAValueB = new LongMeta(Map.of("a", "b"), false);
    LongMeta keyBValueA = new LongMeta(Map.of("b", "a"), false);

    assertNotEquals(keyAValueB, keyBValueA);
    assertNotEquals(keyAValueB.getLongHashCode(), keyBValueA.getLongHashCode());
  }

  @Test
  void toStringIncludesMetadata() {
    assertTrue(new LongMeta(Map.of("1", "2"), true).toString().contains("LongMeta{"));
  }
}
