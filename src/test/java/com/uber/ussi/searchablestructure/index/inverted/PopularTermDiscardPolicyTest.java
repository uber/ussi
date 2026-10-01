/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PopularTermDiscardPolicyTest {

  @Test
  void doesDiscardPopularTermsWhenMaxFractionIsBelowOne() {
    NamespaceConfig config =
        NamespaceConfig.builder()
            .minTermsAndValuesLength(0)
            .maxTermsAndValuesLength(100)
            .maxCacheSize(100)
            .cacheType("scan")
            .indexType("inverted_term")
            .indexParams(Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5"))
            .comparatorType("jaccard")
            .comparatorNormalizerType("complement")
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(100)
            .build();
    assertTrue(PopularTermDiscardPolicy.doesDiscardPopularTerms(config));
    assertFalse(PopularTermDiscardPolicy.doesDiscardPopularTerms(1.0));
  }

  @Test
  void discardedTermsOfMarksTermsAboveTheRowFractionThreshold() {
    LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>();
    rows.put(1L, terms(new long[] {10, 20}));
    rows.put(2L, terms(new long[] {10, 30}));
    rows.put(3L, terms(new long[] {10, 40}));
    rows.put(4L, terms(new long[] {50}));

    LongHashSet discarded = PopularTermDiscardPolicy.discardedTermsOf(rows, 0.5);

    assertEquals(1, discarded.size());
    assertTrue(discarded.contains(10L));
  }

  private static LongTermsAndValues terms(long[] termValues) {
    float[] values = new float[termValues.length];
    for (int i = 0; i < values.length; ++i) {
      values[i] = 1f;
    }
    return LongTermsAndValuesTestFactory.create(termValues, values, termValues.length);
  }
}
