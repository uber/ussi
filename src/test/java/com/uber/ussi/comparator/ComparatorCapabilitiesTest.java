/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparatornormalizer.IdentityComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorCapabilitiesTest {

  @Test
  void reportsConjunctionDotProductAndKeyShareCapabilities() {
    Comparator l2 = ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
    Comparator jaccard =
        ComparatorFactory.createComparator("jaccard", Map.of(), new IdentityComparatorNormalizer());
    Comparator ngld =
        ComparatorFactory.createComparator(
            "ngld", Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "levenshtein"), new IdentityComparatorNormalizer());

    assertTrue(ComparatorCapabilities.isDotProductScored(l2));
    assertTrue(ComparatorCapabilities.conjunctionScored(jaccard).isPresent());
    assertTrue(ComparatorCapabilities.keyShareBounded(jaccard).isPresent());
    assertTrue(ComparatorCapabilities.keyShareBounded(ngld).isPresent());
    assertFalse(ComparatorCapabilities.conjunctionScored(ngld).isPresent());
    assertFalse(ComparatorCapabilities.isDotProductScored(jaccard));
  }

  @Test
  void requireDotProductScoredRejectsOtherComparators() {
    Comparator jaccard =
        ComparatorFactory.createComparator("jaccard", Map.of(), new IdentityComparatorNormalizer());

    assertThrows(
        IllegalArgumentException.class, () -> ComparatorCapabilities.requireDotProductScored(jaccard));
  }
}
