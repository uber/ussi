package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.index.inverted.generator.InvertedList;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.ConfigKeys;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
class SignatureIndexTest {
  /** Test indexes hold far fewer rows than a shard's minimum, so they have one shard. */
  private static final int ONLY_SHARD = 0;

  private static final float DELTA = 1e-6f;

  @Test
  void theCollidingSignaturesOfOneRecordCollapseToASingleKey() {
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash"),
            longObjectMap(7, jaccard(new long[] {11}, 1f)),
            longObjectMap());

    assertEquals(1, index.getNumIndexedKeysForTests(ONLY_SHARD));
    assertEquals(
        1, index.getKeysAndUniTransformedValues(jaccard(new long[] {11}, 1f)).length);
    // A single-term record hashes to one signature every time, and getKeys() owes distinct keys.
    long[] signatures = index.getKeys(jaccard(new long[] {11}, 1f));
    assertEquals(1, signatures.length);
    assertArrayEquals(new long[] {7}, index.getRowNumsForKeyForTests(ONLY_SHARD, signatures[0]));
  }

  @Test
  void identicalRecordsAreRetrievedAndScoredUsingOriginalTermsAndValues() {
    LongTermsAndValues matching = jaccard(sequentialTerms(300, 1), repeatedValue(1f, 300));
    LongTermsAndValues disjoint = jaccard(sequentialTerms(300, 1001), repeatedValue(1f, 300));
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash"), longObjectMap(1, matching, 2, disjoint), longObjectMap());

    List<RowNumAndSimilarity> results = index.getNearestNeighborRowNums(2, matching, MetaFilter.empty());

    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(1.0f, results.get(0).getSimilarity(), DELTA);
    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(index.getSimilarRowNums(1.0f, matching, MetaFilter.empty())));
  }

  @Test
  void metadataFilteringAndDeletionApplyToSignatureCandidates() {
    LongTermsAndValues record = jaccard(sequentialTerms(300, 1), repeatedValue(1f, 300));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, new LongMeta(Map.of("city", "sf"), false));
    SignatureIndex index =
        new SignatureIndex(config("jaccard", "minhash"), longObjectMap(1, record), metadata);
    MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));

    assertEquals(List.of(1L), rowNumsNearestFirst(index.getNearestNeighborRowNums(1, record, sf)));
    assertTrue(index.delete(1));
    assertFalse(index.delete(1));
    assertTrue(index.getNearestNeighborRowNums(1, record, sf).isEmpty());
  }

  @Test
  void weightedSignatureIndexSupportsRuzicka() {
    LongTermsAndValues record = ruzicka(sequentialTerms(300, 1), repeatedIncreasingValues(300));
    SignatureIndex index =
        new SignatureIndex(config("ruzicka", "icws"), longObjectMap(1, record), longObjectMap());

    List<RowNumAndSimilarity> results = index.getNearestNeighborRowNums(1, record, MetaFilter.empty());

    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(1.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void rowsWithOnlyFilteredTermsHaveNoSignatureInvertedLists() {
    LongTermsAndValues record = jaccard(new long[] {11}, 1f);
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            longObjectMap(1, record, 2, record),
            longObjectMap());

    assertArrayEquals(new long[] {11}, index.getDiscardedTermsForTests());
    assertEquals(0, index.getNumIndexedKeysForTests(ONLY_SHARD));
    assertEquals(2, index.size());
    assertTrue(index.getNearestNeighborRowNums(2, record, MetaFilter.empty()).isEmpty());
  }

  @Test
  void keepsSignaturesAppearingInEveryRowBecauseFrequencyIsCountedOverTermsAlone() {
    // Two rows with the same terms have the same signatures, so every signature here is in every
    // row. None of their terms is popular, so nothing is discarded, and the lists survive: a
    // frequency rule counted over the keys rather than over the terms would have emptied them.
    LongTermsAndValues record = jaccard(new long[] {11, 12, 13}, 1f, 1f, 1f);
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "1.0")),
            longObjectMap(1, record, 2, record),
            longObjectMap());

    assertArrayEquals(new long[0], index.getDiscardedTermsForTests());
    assertTrue(
        index.getNumIndexedKeysForTests(ONLY_SHARD) > 0, "the shared signatures are still keys");
    assertEquals(
        List.of(1L, 2L),
        rowNumsNearestFirst(index.getNearestNeighborRowNums(2, record, MetaFilter.empty())));
  }

  /**
   * No signature generator leaves nothing to key the lists by, a property of the config rather than
   * the rows. One that could generate them names the missing param. One that could not does not.
   */
  @Test
  void aComparatorWithoutSignaturesIsRejectedBeforeBuildingRows() {
    NamespaceConfig exactJaccardConfig = config("jaccard", null);

    IllegalArgumentException missingParam =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SignatureIndex(exactJaccardConfig, longObjectMap(), longObjectMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SignatureIndex(
                exactJaccardConfig,
                longObjectMap(1, jaccard(new long[] {1}, 1f)),
                longObjectMap()));
    IllegalArgumentException noSuchParam =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SignatureIndex(
                    config("l2", null), longObjectMap(1, l2(new long[] {1}, 1f)), longObjectMap()));

    assertTrue(
        missingParam.getMessage().contains("needs signature_generator"),
        missingParam.getMessage());
    assertTrue(
        noSuchParam.getMessage().contains("comparatorType l2 cannot generate"),
        noSuchParam.getMessage());
  }

  /** Signature keys say nothing about values, so the merge must verify through the comparator. */
  @Test
  void mergeResultsMatchFilteredScanForSignatureKeys() {
    for (String[] comparatorAndGenerator :
        new String[][] {{"jaccard", "minhash"}, {"ruzicka", "icws"}}) {
      String comparatorType = comparatorAndGenerator[0];
      String signatureGeneratorType = comparatorAndGenerator[1];
      Random random = new Random(41_957L + comparatorType.hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      for (long rowNum = 1; rowNum <= 40; ++rowNum) {
        rows.put(rowNum, randomRow(random, comparatorType));
      }
      SignatureIndex filteredScanIndex =
          new SignatureIndex(config(comparatorType, signatureGeneratorType), rows, longObjectMap());
      SignatureIndex mergeIndex =
          new SignatureIndex(
              config(comparatorType, signatureGeneratorType, mergeIndexParams()),
              rows,
              longObjectMap());

      for (int queryIndex = 0; queryIndex < 20; ++queryIndex) {
        LongTermsAndValues query = randomRow(random, comparatorType);
        int k = 1 + random.nextInt(8);
        float minSimilarity = new float[] {0.0f, 0.2f, 0.5f}[random.nextInt(3)];

        assertEquals(
            rowNumsAndSimilarities(
                filteredScanIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty())),
            rowNumsAndSimilarities(
                mergeIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty())),
            comparatorType + " nearest queryIndex=" + queryIndex + " k=" + k);
        assertEquals(
            rowNumsAndSimilarities(
                filteredScanIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty())),
            rowNumsAndSimilarities(
                mergeIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty())),
            comparatorType
                + " minimum similarity queryIndex="
                + queryIndex
                + " min="
                + minSimilarity);
      }
    }
  }

  /**
   * Only exact sparse keys let the merge score from the conjunction, so only they carry the
   * inverted-list values.
   */
  @Test
  void mergeMaterializesInvertedListValuesOnlyForExactKeys()
      throws ReflectiveOperationException {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap(1, jaccard(new long[] {1, 2}, 1, 1));

    assertTrue(
        hasInvertedListValues(
            new TermIndex(termConfig("jaccard", mergeIndexParams()), rows, longObjectMap())),
        "merge over exact terms");
    assertFalse(
        hasInvertedListValues(
            new SignatureIndex(
                config("jaccard", "minhash", mergeIndexParams()), rows, longObjectMap())),
        "merge over signatures");
    assertFalse(
        hasInvertedListValues(
            new TermIndex(termConfig("jaccard", Map.of()), rows, longObjectMap())),
        "filtered scan");
  }

  @SuppressWarnings("unchecked")
  private static boolean hasInvertedListValues(BaseInvertedIndex index)
      throws ReflectiveOperationException {
    Field field = BaseInvertedIndex.class.getDeclaredField("keyToInvertedListByShard");
    field.setAccessible(true);
    List<LongObjectHashMap<InvertedList>> invertedListsByShard =
        (List<LongObjectHashMap<InvertedList>>) field.get(index);
    LongObjectHashMap<InvertedList> invertedLists = invertedListsByShard.get(ONLY_SHARD);
    assertFalse(invertedLists.isEmpty(), "the index should have at least one sparse key");
    for (LongObjectCursor<InvertedList> entry : invertedLists) {
      if (entry.value.getValues().length != entry.value.size()) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, String> mergeIndexParams() {
    return Map.of(
        ConfigKeys.CANDIDATE_GENERATOR,
        NamespaceConfig.CandidateGeneratorType.SPARS_MERGE.getParamValue());
  }

  private static NamespaceConfig termConfig(
      String comparatorType, Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(1000)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_term")
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues randomRow(Random random, String comparatorType) {
    int numTerms = 1 + random.nextInt(6);
    TreeMap<Long, Float> valuesByTerm = new TreeMap<>();
    while (valuesByTerm.size() < numTerms) {
      valuesByTerm.put((long) random.nextInt(20), 0.5f * (1 + random.nextInt(6)));
    }
    long[] terms = new long[valuesByTerm.size()];
    float[] values = new float[valuesByTerm.size()];
    int index = 0;
    for (Map.Entry<Long, Float> entry : valuesByTerm.entrySet()) {
      terms[index] = entry.getKey();
      values[index] = entry.getValue();
      ++index;
    }
    return "jaccard".equals(comparatorType) ? jaccard(terms, values) : ruzicka(terms, values);
  }

  private static List<String> rowNumsAndSimilarities(List<RowNumAndSimilarity> results) {
    return results.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(result -> result.getRowNum() + "=" + String.format("%.5f", result.getSimilarity()))
        .toList();
  }

  private static NamespaceConfig config(String comparatorType, String signatureGeneratorType) {
    return config(comparatorType, signatureGeneratorType, Map.of());
  }

  private static NamespaceConfig config(
      String comparatorType, String signatureGeneratorType, Map<String, String> indexParams) {
    Map<String, String> comparatorParams =
        signatureGeneratorType == null
            ? Map.of()
            : Map.of(ConfigKeys.SIGNATURE_GENERATOR, signatureGeneratorType);
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(1000)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_signature")
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        .comparatorParams(comparatorParams)
        .comparatorNormalizerType(comparatorType.equals("l2") ? "reciprocal" : "identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues jaccard(long[] terms, float... values) {
    return LongTermsAndValuesTestFactory.create(terms, values, terms.length);
  }

  private static LongTermsAndValues ruzicka(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(value);
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static LongTermsAndValues l2(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += (double) value * value;
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static long[] sequentialTerms(int size, long firstTerm) {
    long[] terms = new long[size];
    for (int i = 0; i < size; ++i) {
      terms[i] = firstTerm + i;
    }
    return terms;
  }

  private static float[] repeatedValue(float value, int size) {
    float[] values = new float[size];
    java.util.Arrays.fill(values, value);
    return values;
  }

  private static float[] repeatedIncreasingValues(int size) {
    float[] values = new float[size];
    for (int i = 0; i < size; ++i) {
      values[i] = i + 1;
    }
    return values;
  }

  private static List<Long> rowNumsNearestFirst(List<RowNumAndSimilarity> results) {
    return results.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(RowNumAndSimilarity::getRowNum)
        .toList();
  }

  /** The same index keyed by signatures drawn from a sequence's term multiset. */
  @Nested
  class OnSequences {
    private static final float DELTA = 1e-6f;
    private static final float[] NO_VALUES = new float[0];
    private static final int ALPHABET_SIZE = 40;
    private static final int BASE_LENGTH = 300;

    /**
     * MinSimilaritys that admit a handful of edits over sequences this long. GLD scores an edit count
     * through the reciprocal normalizer, so 0.25 admits three edits and 0.1 admits nine. NGLD
     * divides by the lengths, so the same counts land near the top of its range.
     */
    private static final Map<String, float[]> MIN_SIMILARITIES =
        Map.of("gld", new float[] {0.1f, 0.15f, 0.25f}, "ngld", new float[] {0.9f, 0.95f, 0.97f});

    private static final List<String> COMPARATOR_TYPES = List.of("gld", "ngld");

    /**
     * Verification reads the sequences rather than the signatures, so a row the lists surface is
     * scored exactly. Nothing that fails the minimum similarity survives, and nothing that passes is
     * scored as anything other than what a scan would report.
     */
    @Test
    void everySequenceTheSignatureListsSurfaceIsScoredExactly() {
      for (String comparatorType : COMPARATOR_TYPES) {
        Random random = new Random(60_221L);
        List<long[]> bases = bases(random, 6);
        LongObjectHashMap<LongTermsAndValues> rows = variantsOf(random, bases, 8);
        SignatureIndex index = new SignatureIndex(config(comparatorType), rows, longObjectMap());
        ScanIndex bruteForce = new ScanIndex(scanConfig(comparatorType), rows, longObjectMap());

        for (int trial = 0; trial < 12; ++trial) {
          LongTermsAndValues query = query(random, bases, trial);
          for (float minSimilarity : MIN_SIMILARITIES.get(comparatorType)) {
            Map<Long, Float> exact =
                similaritiesByRowNum(bruteForce.getSimilarRowNums(minSimilarity, query, null));

            for (RowNumAndSimilarity result : index.getSimilarRowNums(minSimilarity, query, null)) {
              String message =
                  String.format(
                      "%s trial=%d minSimilarity=%s rowNum=%d",
                      comparatorType, trial, minSimilarity, result.getRowNum());
              Float expected = exact.get(result.getRowNum());
              assertTrue(expected != null, message + " is not similar enough to qualify");
              assertEquals(expected, result.getSimilarity(), DELTA, message);
            }
          }
        }
      }
    }

    /**
     * The prefix bound turns the minimum similarity into the share of signatures a qualifying
     * candidate has to collide on, widened by the generator's margin. Recall is therefore high rather
     * than exact, and a bound derived in the wrong direction would show up here as a collapse.
     */
    @Test
    void theSignatureListsRecallNearlyEverySequenceAScanFinds() {
      for (String comparatorType : COMPARATOR_TYPES) {
        Random random = new Random(18_446L);
        List<long[]> bases = bases(random, 6);
        LongObjectHashMap<LongTermsAndValues> rows = variantsOf(random, bases, 8);
        SignatureIndex index = new SignatureIndex(config(comparatorType), rows, longObjectMap());
        ScanIndex bruteForce = new ScanIndex(scanConfig(comparatorType), rows, longObjectMap());
        int numQualifying = 0;
        int numRecalled = 0;

        for (int trial = 0; trial < 12; ++trial) {
          LongTermsAndValues query = query(random, bases, trial);
          for (float minSimilarity : MIN_SIMILARITIES.get(comparatorType)) {
            List<Long> qualifying =
                rowNumsSharingAnTerm(
                    bruteForce.getSimilarRowNums(minSimilarity, query, null), rows, query);
            LongHashSet found =
                LongHashSet.from(
                    index.getSimilarRowNums(minSimilarity, query, null).stream()
                        .mapToLong(RowNumAndSimilarity::getRowNum)
                        .toArray());
            numQualifying += qualifying.size();
            for (long rowNum : qualifying) {
              if (found.contains(rowNum)) {
                ++numRecalled;
              }
            }
          }
        }

        assertTrue(numQualifying > 60, comparatorType + " corpus produced too few matches to judge");
        assertTrue(
            numRecalled >= 0.9 * numQualifying,
            String.format(
                "%s recalled %d of %d qualifying rows", comparatorType, numRecalled, numQualifying));
      }
    }

    /** An exact match shares every term at the same count, so its signatures all collide. */
    @Test
    void anIdenticalSequenceIsFoundThroughItsSignatures() {
      for (String comparatorType : COMPARATOR_TYPES) {
        LongTermsAndValues sequence = sequence(new Random(7L), 400);
        SignatureIndex index =
            new SignatureIndex(config(comparatorType), longObjectMap(1, sequence), longObjectMap());

        List<RowNumAndSimilarity> results =
            index.getNearestNeighborRowNums(1, sequence, MetaFilter.empty());

        assertEquals(1, results.size(), comparatorType + " " + results);
        assertEquals(1L, results.get(0).getRowNum(), comparatorType);
        assertEquals(1.0f, results.get(0).getSimilarity(), DELTA, comparatorType);
      }
    }

    /**
     * The hybrid structure routes by term count, so a corpus straddling the cutoff exercises both
     * halves at once and every row still has to come back scored exactly.
     *
     * <p>Both sequence comparators are run. They normalize differently, and the minimum
     * similarity a search carries is what the routing prunes against, so the two do not
     * exercise the same decision.
     */
    @Test
    void theHybridStructureRoutesSequencesByTermCount() {
      for (String comparatorType : List.of("gld", "ngld")) {
        Random random = new Random(31_337L);
        long[] shortBase = sequence(random, 20).getTerms();
        long[] longBase = sequence(random, SignatureIndex.NUM_SIGNATURES_PER_ROW + 40).getTerms();
        LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
        for (long rowNum = 1; rowNum <= 8; ++rowNum) {
          rows.put(rowNum, perturbed(random, shortBase, 1 + random.nextInt(3)));
        }
        for (long rowNum = 9; rowNum <= 16; ++rowNum) {
          rows.put(rowNum, perturbed(random, longBase, 1 + random.nextInt(8)));
        }
        HybridIndex index = new HybridIndex(hybridConfig(comparatorType), rows, longObjectMap());
        ScanIndex bruteForce = new ScanIndex(scanConfig(comparatorType), rows, longObjectMap());

        assertEquals(8, index.getNumExactRowsForTests(), comparatorType);
        assertEquals(8, index.getNumSignatureRowsForTests(), comparatorType);

        for (int trial = 0; trial < 10; ++trial) {
          long[] base = trial % 2 == 0 ? shortBase : longBase;
          LongTermsAndValues query = perturbed(random, base, 1 + random.nextInt(3));
          Map<Long, Float> exact =
              similaritiesByRowNum(bruteForce.getSimilarRowNums(0.0f, query, null));
          // The threshold comes from the scan rather than being fixed, because the two
          // comparators normalize differently: one number admits every row for one of them and
          // no row for the other. The third best admits the query's own cluster either way.
          float minSimilarity = nthHighest(exact.values(), 3);

          List<RowNumAndSimilarity> results =
              index.getSimilarRowNums(minSimilarity, query, MetaFilter.empty());
          String where = comparatorType + " trial=" + trial;
          assertTrue(!results.isEmpty(), where + " found nothing in its own cluster");
          for (RowNumAndSimilarity result : results) {
            Float expected = exact.get(result.getRowNum());
            String message = where + " rowNum=" + result.getRowNum();
            assertTrue(expected != null, message + " is not similar enough to qualify");
            assertEquals(expected, result.getSimilarity(), DELTA, message);
          }
        }
      }
    }

    /** The nth highest of the similarities, or the lowest when there are fewer than n. */
    private static float nthHighest(Collection<Float> similarities, int n) {
      List<Float> ranked = new ArrayList<>(similarities);
      ranked.sort(java.util.Comparator.reverseOrder());
      return ranked.get(Math.min(n - 1, ranked.size() - 1));
    }

    private static Map<Long, Float> similaritiesByRowNum(List<RowNumAndSimilarity> results) {
      Map<Long, Float> similarities = new HashMap<>();
      for (RowNumAndSimilarity result : results) {
        similarities.put(result.getRowNum(), result.getSimilarity());
      }
      return similarities;
    }

    /** Disjoint multisets draw no shared sample, so the lists cannot reach those rows at all. */
    private static List<Long> rowNumsSharingAnTerm(
        List<RowNumAndSimilarity> results,
        LongObjectHashMap<LongTermsAndValues> rows,
        LongTermsAndValues query) {
      LongHashSet queryTerms = LongHashSet.from(query.getTerms());
      List<Long> rowNums = new ArrayList<>(results.size());
      for (RowNumAndSimilarity result : results) {
        LongTermsAndValues row = rows.get(result.getRowNum());
        for (int index = 0; index < row.termsLength(); ++index) {
          if (queryTerms.contains(row.getTerm(index))) {
            rowNums.add(result.getRowNum());
            break;
          }
        }
      }
      return rowNums;
    }

    /**
     * An edit distance only separates records that are nearly alike, so a corpus it suits is one of
     * clusters: a few unrelated sequences and the variants a handful of edits away from each.
     */
    private static List<long[]> bases(Random random, int numBases) {
      List<long[]> bases = new ArrayList<>(numBases);
      for (int index = 0; index < numBases; ++index) {
        bases.add(sequence(random, BASE_LENGTH).getTerms());
      }
      return bases;
    }

    private static LongObjectHashMap<LongTermsAndValues> variantsOf(
        Random random, List<long[]> bases, int variantsPerBase) {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      long rowNum = 1;
      for (long[] base : bases) {
        for (int variant = 0; variant < variantsPerBase; ++variant) {
          rows.put(rowNum++, perturbed(random, base, 1 + random.nextInt(6)));
        }
      }
      return rows;
    }

    private static LongTermsAndValues query(Random random, List<long[]> bases, int trial) {
      return perturbed(random, bases.get(trial % bases.size()), 1 + random.nextInt(4));
    }

    /** Applies {@code numEdits} substitutions, insertions, and deletions at random positions. */
    private static LongTermsAndValues perturbed(Random random, long[] base, int numEdits) {
      List<Long> terms = new ArrayList<>(base.length + numEdits);
      for (long term : base) {
        terms.add(term);
      }
      for (int edit = 0; edit < numEdits; ++edit) {
        int position = random.nextInt(terms.size());
        switch (random.nextInt(3)) {
          case 0 -> terms.set(position, (long) random.nextInt(ALPHABET_SIZE));
          case 1 -> terms.add(position, (long) random.nextInt(ALPHABET_SIZE));
          default -> terms.remove(position);
        }
      }
      long[] perturbed = new long[terms.size()];
      for (int index = 0; index < perturbed.length; ++index) {
        perturbed[index] = terms.get(index);
      }
      return LongTermsAndValuesTestFactory.create(perturbed, NO_VALUES, perturbed.length);
    }

    private static LongTermsAndValues sequence(Random random, int length) {
      long[] terms = new long[length];
      for (int index = 0; index < terms.length; ++index) {
        terms[index] = random.nextInt(ALPHABET_SIZE);
      }
      return LongTermsAndValuesTestFactory.create(terms, NO_VALUES, terms.length);
    }

    private static NamespaceConfig config(String comparatorType) {
      return config(comparatorType, "inverted_signature");
    }

    private static NamespaceConfig hybridConfig(String comparatorType) {
      return config(comparatorType, "inverted_hybrid");
    }

    private static NamespaceConfig scanConfig(String comparatorType) {
      return NamespaceConfig.builder()
          .minTermsAndValuesLength(0)
          .maxTermsAndValuesLength(1000)
          .maxCacheSize(100)
          .cacheType("scan")
          .indexType("scan")
          .comparatorType(comparatorType)
          .comparatorNormalizerType(comparatorType.equals("gld") ? "reciprocal" : "complement")
          .maxNumSearchableStructures(3)
          .maxNumSimilarities(100)
          .build();
    }

    private static NamespaceConfig config(String comparatorType, String indexType) {
      return NamespaceConfig.builder()
          .minTermsAndValuesLength(0)
          .maxTermsAndValuesLength(1000)
          .maxCacheSize(100)
          .cacheType("scan")
          .indexType(indexType)
          .comparatorType(comparatorType)
          .comparatorParams(Map.of(ConfigKeys.SIGNATURE_GENERATOR, "icws"))
          // GLD reports an unbounded distance, so it needs a normalizer that maps one onto [0, 1].
          .comparatorNormalizerType(comparatorType.equals("gld") ? "reciprocal" : "complement")
          .maxNumSearchableStructures(3)
          .maxNumSimilarities(100)
          .build();
    }
  }
}
