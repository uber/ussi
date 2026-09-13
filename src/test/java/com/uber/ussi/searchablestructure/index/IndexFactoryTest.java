package com.uber.ussi.searchablestructure.index;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.dense.DenseMatrixIndex;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import com.uber.ussi.searchablestructure.index.sparse.TermIndex;
import com.uber.ussi.searchablestructure.index.sparse.SequenceIndex;
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
  void createIndexCreatesTermIndex() {
    Index index =
        IndexFactory.createIndex(
            validConfig().indexType("TERM").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(TermIndex.class, index);
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

  private static final IndexTypePredicateCase[] SPARSE_GENERATOR_CASES = {
    new IndexTypePredicateCase("term", true),
    new IndexTypePredicateCase("sparse", true),
    new IndexTypePredicateCase("signature", true),
    new IndexTypePredicateCase("generic", false),
    new IndexTypePredicateCase("dense", false),
  };

  private static final IndexTypePredicateCase[] MERGE_VERIFICATION_CASES = {
    new IndexTypePredicateCase("sparse", true),
    new IndexTypePredicateCase("signature", true),
    new IndexTypePredicateCase("term", false),
    new IndexTypePredicateCase("generic", false),
  };

  @Test
  void supportsSparseCandidateGeneratorCases() {
    for (IndexTypePredicateCase testCase : SPARSE_GENERATOR_CASES) {
      assertEquals(
          testCase.expected,
          IndexFactory.supportsSparseCandidateGenerator(testCase.indexType),
          testCase.indexType);
    }
  }

  @Test
  void mergeRequiresCandidateVerificationCases() {
    for (IndexTypePredicateCase testCase : MERGE_VERIFICATION_CASES) {
      assertEquals(
          testCase.expected,
          IndexFactory.mergeRequiresCandidateVerification(testCase.indexType),
          testCase.indexType);
    }
  }

  private record IndexTypePredicateCase(String indexType, boolean expected) {}

  @Test
  void createIndexCreatesSequenceIndex() {
    Index index =
        IndexFactory.createIndex(
            sequenceConfig().indexType("SEQUENCE").build(), longObjectMap(), longObjectMap());

    assertInstanceOf(SequenceIndex.class, index);
  }

  @Test
  void getRecordTypeCases() {
    assertEquals(RecordType.DENSE, IndexFactory.getRecordType("dense"));
    assertEquals(RecordType.SPARSE, IndexFactory.getRecordType("term"));
    assertEquals(RecordType.SPARSE, IndexFactory.getRecordType("signature"));
    assertEquals(RecordType.SPARSE, IndexFactory.getRecordType("SPARSE"));
    assertEquals(RecordType.SEQUENCE, IndexFactory.getRecordType("sequence"));
    // The generic index scores through the comparator without reading a record's layout itself.
    assertNull(IndexFactory.getRecordType("generic"));
    // An unknown index type has no record type to require, and createIndex reports the name.
    assertNull(IndexFactory.getRecordType("hnsw"));
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
