package com.uber.ussi.searchablestructure.index;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.dense.DenseMatrixIndex;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import com.uber.ussi.searchablestructure.index.sparse.InvertedIndex;
import com.uber.ussi.searchablestructure.index.sparse.SignatureIndex;
import com.uber.ussi.searchablestructure.index.sparse.SparseIndex;
import com.uber.ussi.utils.Constants;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexFactoryTest {

  @Test
  void createIndexCreatesGenericIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("GENERIC").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(GenericIndex.class, index);
  }

  @Test
  void createIndexCreatesDenseIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("DENSE").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(DenseMatrixIndex.class, index);
  }

  @Test
  void createIndexCreatesInvertedIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("INVERTED").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(InvertedIndex.class, index);
  }

  @Test
  void createIndexCreatesSignatureIndex() {
    Index index =
        IndexFactory.createIndex(
            signatureConfig().indexType("SIGNATURE").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(SignatureIndex.class, index);
  }

  @Test
  void createIndexCreatesHybridSparseIndex() {
    Index index =
        IndexFactory.createIndex(
            signatureConfig().indexType("SPARSE").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(SparseIndex.class, index);
  }

  @Test
  void createIndexRejectsUnsupportedIndexType() {
    NamespaceConfig config = validConfig().indexType("hnsw").build();

    assertThrows(
        IndexCreationError.class,
        () -> IndexFactory.createIndex(config, longObjectMap(), longObjectMap()));
  }

  private static NamespaceConfig.Builder validConfig() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(10)
        .cacheType("generic")
        .indexType("generic")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10);
  }

  private static NamespaceConfig.Builder signatureConfig() {
    return validConfig()
        .maxTermsAndValuesLength(Constants.NUM_SIGNATURES_PER_ID + 1)
        .comparatorType("jaccard")
        .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash"))
        .comparatorNormalizerType("identity");
  }
}
