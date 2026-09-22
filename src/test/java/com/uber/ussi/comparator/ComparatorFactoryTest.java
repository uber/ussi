/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which comparator, distance, and signature generator a configuration names. */
class ComparatorFactoryTest {

  /** A distance named by nothing at all leaves the one a sequence comparator assumes. */
  @Test
  void leavesTheDefaultSequenceDistanceWhenNoneIsNamed() {
    for (Map<String, String> params :
        java.util.List.of(
            Map.<String, String>of(),
            Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, ""),
            Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "   "))) {
      SequenceDistance distance = ComparatorFactory.createSequenceDistance(params);

      assertNotNull(distance);
      // Insertion, deletion, and substitution, which is what rewriting one term costs under it.
      assertEquals(1L, distance.getDistance(sequence(1, 2), sequence(1, 3), 2));
    }
  }

  @Test
  void namesTheSequenceDistanceItIsGivenAndRejectsOneItHasNot() {
    // Insertion and deletion alone, so rewriting a term costs both.
    assertEquals(
        2L,
        ComparatorFactory.createSequenceDistance(Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "lcs"))
            .getDistance(sequence(1, 2), sequence(1, 3), 4));

    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createSequenceDistance(
                Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "sideways")));
  }

  /** A generator is optional, so a configuration naming none asks for none. */
  @Test
  void createsNoSignatureGeneratorWhenNoneIsNamed() {
    for (String named : new String[] {null, "", "   "}) {
      Map<String, String> comparatorParams =
          named == null ? Map.of() : Map.of(ConfigKeys.SIGNATURE_GENERATOR, named);

      assertNull(
          ComparatorFactory.createSignatureGenerator(config("jaccard", comparatorParams)),
          "named " + named);
    }
  }

  @Test
  void createsTheSignatureGeneratorItIsGiven() {
    assertNotNull(
        ComparatorFactory.createSignatureGenerator(
            config("jaccard", Map.of(ConfigKeys.SIGNATURE_GENERATOR, "minhash"))));
  }

  /** A comparator no type names cannot be asked which generators it supports. */
  @Test
  void rejectsAComparatorTypeItHasNot() {
    ComparatorCreationError error =
        assertThrows(
            ComparatorCreationError.class,
            () ->
                ComparatorFactory.createSignatureGenerator(
                    config("sideways", Map.of(ConfigKeys.SIGNATURE_GENERATOR, "minhash"))));

    assertTrue(error.getMessage().contains("sideways"), error.getMessage());
  }

  /** A comparator reading no counts supports no weighted sampler, so naming one is refused. */
  @Test
  void rejectsAGeneratorTheComparatorSupportsNone() {
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createSignatureGenerator(
                config("l2", Map.of(ConfigKeys.SIGNATURE_GENERATOR, "minhash"))));
  }

  private static LongTermsAndValues sequence(long... terms) {
    return LongTermsAndValuesTestFactory.create(terms, new float[0], terms.length);
  }

  private static NamespaceConfig config(
      String comparatorType, Map<String, String> comparatorParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType(comparatorType)
        .comparatorParams(comparatorParams)
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5)
        .build();
  }
}
