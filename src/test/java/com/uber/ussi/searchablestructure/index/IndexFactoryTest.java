package com.uber.ussi.searchablestructure.index;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.inverted.HybridIndex;
import com.uber.ussi.searchablestructure.index.inverted.SignatureIndex;
import com.uber.ussi.searchablestructure.index.inverted.TermIndex;
import com.uber.ussi.searchablestructure.index.matrix.MatrixIndex;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.utils.Constants;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexFactoryTest {

  @Test
  void createIndexCreatesScanIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("scan").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(ScanIndex.class, index);
  }

  @Test
  void createIndexCreatesMatrixIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("matrix").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(MatrixIndex.class, index);
  }

  @Test
  void createIndexCreatesTermIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("inverted_term").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(TermIndex.class, index);
  }

  /** A structure names how an index is keyed; the type it stores comes from the comparator. */
  @Test
  void createIndexCreatesTermIndexForSequenceComparators() {
    Index index =
        IndexFactory.createIndex(
            sequenceConfig().indexType("inverted_term").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(TermIndex.class, index);
  }

  @Test
  void createIndexCreatesSignatureIndex() {
    Index index =
        IndexFactory.createIndex(
            signatureConfig().indexType("inverted_signature").build(),
            longObjectMap(),
            longObjectMap());

    assertInstanceOf(SignatureIndex.class, index);
  }

  @Test
  void createIndexCreatesHybridIndex() {
    Index index =
        IndexFactory.createIndex(
            signatureConfig().indexType("inverted_hybrid").build(),
            longObjectMap(),
            longObjectMap());

    assertInstanceOf(HybridIndex.class, index);
  }

  @Test
  void createIndexAcceptsAnIndexTypeInAnyCase() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("Inverted_Term").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(TermIndex.class, index);
  }

  @Test
  void createIndexRejectsUnsupportedIndexType() {
    NamespaceConfig config = validConfig().indexType("hnsw").build();

    assertThrows(
        IndexCreationError.class,
        () -> IndexFactory.createIndex(config, longObjectMap(), longObjectMap()));
  }

  /** A comparator reading no type the structure keeps leaves no index to build. */
  @Test
  void createIndexRejectsAComparatorThatReadsNothingTheStructureStores() {
    NamespaceConfig config = sequenceConfig().indexType("inverted_signature").build();

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> IndexFactory.createIndex(config, longObjectMap(), longObjectMap()));

    assertTrue(
        error.getMessage().contains("stores order_agnostic_sparse records")
            && error.getMessage().contains("comparatorType ngld reads sequence"),
        error.getMessage());
  }

  private static NamespaceConfig.Builder validConfig() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10);
  }

  private static NamespaceConfig.Builder sequenceConfig() {
    return validConfig().comparatorType("ngld").comparatorNormalizerType("complement");
  }

  private static NamespaceConfig.Builder signatureConfig() {
    return validConfig()
        .maxTermsAndValuesLength(Constants.NUM_SIGNATURES_PER_ID + 1)
        .comparatorType("jaccard")
        .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash"))
        .comparatorNormalizerType("identity");
  }
}
