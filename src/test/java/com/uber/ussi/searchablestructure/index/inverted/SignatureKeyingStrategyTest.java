package com.uber.ussi.searchablestructure.index.inverted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.signaturegenerator.SignatureGenerator;
import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory;
import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.utils.ConfigKeys;
import com.uber.ussi.utils.MathUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureKeyingStrategyTest {
  private static final double EPSILON_9 = 1e-9;

  /**
   * The bound the measure states is a share of a record's signatures, so the generator's margin
   * relaxes it before the prefix is taken. Jaccard's threshold is already that share, which is why
   * the record's own Uni value adds nothing to it.
   */
  @Test
  void thePrefixRelaxesTheMeasuresSharedBoundByTheGeneratorMargin() {
    SignatureKeyingStrategy strategy =
        strategy(comparator("jaccard", "identity"), SignatureGeneratorType.MINHASH);

    assertEquals(
        60.0 + MathUtils.EPSILON_12,
        strategy.getMaxPrefixSumForSignatures(100, /* recordUniValue */ 8.0, 0.5),
        0.0);
    assertEquals(
        60.0 + MathUtils.EPSILON_12,
        strategy.getMaxPrefixSumForSignatures(100, /* recordUniValue */ 4096.0, 0.5),
        0.0);
  }

  /**
   * A distance threshold reaches the strategy as a similarity, so the conversion into the measure's
   * own units has to happen before the measure states its bound. At minSimilarity 0.8 the ngld
   * budget is 0.2, leaving 7/11 of the signatures to collide; ICWS estimates that to within 0.1.
   */
  @Test
  void thePrefixConvertsASimilarityIntoTheMeasuresOwnUnits() {
    SignatureKeyingStrategy strategy =
        strategy(
            comparator(
                "ngld", "complement", Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "levenshtein")),
            SignatureGeneratorType.ICWS);

    assertEquals(
        47.0 + MathUtils.EPSILON_12,
        strategy.getMaxPrefixSumForSignatures(100, /* recordUniValue */ 6.0, 0.8),
        EPSILON_9);
  }

  /** A signature stands for one draw, whatever the record behind it weighed. */
  @Test
  void everySignatureContributesTheSameUniValue() {
    SignatureKeyingStrategy strategy =
        strategy(comparator("jaccard", "identity"), SignatureGeneratorType.MINHASH);

    assertEquals(1.0, strategy.getSignatureUniTransformedValue(), 0.0);
  }

  @Test
  void thePrefixRejectsArgumentsThatDescribeNoRecord() {
    SignatureKeyingStrategy strategy =
        strategy(comparator("ruzicka", "identity"), SignatureGeneratorType.ICWS);

    assertThrows(
        IllegalArgumentException.class,
        () -> strategy.getMaxPrefixSumForSignatures(-1, 4.0, 0.5));
    assertThrows(
        IllegalArgumentException.class,
        () -> strategy.getMaxPrefixSumForSignatures(10, 4.0, -0.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> strategy.getMaxPrefixSumForSignatures(10, 4.0, 1.1));
  }

  /**
   * The strategy is what a signature-keyed structure cannot be built without, so an absent
   * generator fails here rather than leaving every caller to check for one.
   */
  @Test
  void aComparatorWithoutAConfiguredGeneratorCannotKeyBySignatures() {
    NamespaceConfig config = config("jaccard", "identity", Map.of());

    assertThrows(
        IndexCreationError.class,
        () -> SignatureKeyingStrategy.create(config, comparator("jaccard", "identity")));
  }

  /** A measure that cannot say what its signatures would collide at cannot key by them either. */
  @Test
  void aComparatorThatBoundsNoSignaturesCannotKeyBySignatures() {
    NamespaceConfig config = config("l2", "reciprocal", Map.of());

    assertThrows(
        IndexCreationError.class,
        () -> SignatureKeyingStrategy.create(config, comparator("l2", "reciprocal")));
  }

  @Test
  void theConfiguredGeneratorIsTheOneTheStrategyDrawsFrom() {
    NamespaceConfig config =
        config("jaccard", "identity", Map.of(ConfigKeys.SIGNATURE_GENERATOR, "minhash"));
    Comparator comparator = comparator("jaccard", "identity");

    SignatureKeyingStrategy strategy = SignatureKeyingStrategy.create(config, comparator);

    assertEquals(
        16,
        strategy.getSignatures(
                LongTermsAndValuesTestFactory.create(new long[] {1L}, new float[] {1f}, 1.0), 16)
            .length);
  }

  private static SignatureKeyingStrategy strategy(
      Comparator comparator, SignatureGeneratorType generatorType) {
    SignatureGenerator generator =
        SignatureGeneratorFactory.createSignatureGenerator(generatorType);
    return new SignatureKeyingStrategy(comparator, generator);
  }

  private static Comparator comparator(String comparatorType, String normalizerType) {
    return comparator(comparatorType, normalizerType, Map.of());
  }

  private static Comparator comparator(
      String comparatorType, String normalizerType, Map<String, String> comparatorParams) {
    return ComparatorFactory.createComparator(
        comparatorType,
        comparatorParams,
        ComparatorNormalizerFactory.createComparatorNormalizer(normalizerType, Map.of()));
  }

  private static NamespaceConfig config(
      String comparatorType, String normalizerType, Map<String, String> comparatorParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(1000)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_signature")
        .indexParams(Map.of())
        .comparatorType(comparatorType)
        .comparatorParams(comparatorParams)
        .comparatorNormalizerType(normalizerType)
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }
}
