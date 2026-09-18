package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.utils.ConfigKeys;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Sequences reaching the signature-keyed lists. A sequence's signatures are drawn from its term
 * multiset, so candidates come from the multiset bound the term-keyed lists already use, only
 * estimated rather than computed. The edit distance still verifies order on every survivor.
 */
class SignatureIndexOnSequencesTest {
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
   */
  @Test
  void theHybridStructureRoutesSequencesByTermCount() {
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
    HybridIndex index = new HybridIndex(hybridConfig("ngld"), rows, longObjectMap());
    ScanIndex bruteForce = new ScanIndex(scanConfig("ngld"), rows, longObjectMap());

    assertEquals(8, index.getNumExactRowsForTests());
    assertEquals(8, index.getNumSignatureRowsForTests());

    for (int trial = 0; trial < 10; ++trial) {
      long[] base = trial % 2 == 0 ? shortBase : longBase;
      LongTermsAndValues query = perturbed(random, base, 1 + random.nextInt(3));
      Map<Long, Float> exact =
          similaritiesByRowNum(bruteForce.getSimilarRowNums(0.8f, query, null));

      List<RowNumAndSimilarity> results =
          index.getSimilarRowNums(0.8f, query, MetaFilter.empty());
      assertTrue(!results.isEmpty(), "trial=" + trial + " found nothing in its own cluster");
      for (RowNumAndSimilarity result : results) {
        Float expected = exact.get(result.getRowNum());
        String message = "trial=" + trial + " rowNum=" + result.getRowNum();
        assertTrue(expected != null, message + " is not similar enough to qualify");
        assertEquals(expected, result.getSimilarity(), DELTA, message);
      }
    }
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
