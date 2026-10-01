/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongIntCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Objects;

/**
 * Popularity-based term discard policy for inverted term-keyed indexes.
 *
 * <p>Hybrid and term indexes share this policy so a composite structure can compute discards over
 * its term-keyed partition without depending on {@link BaseInvertedIndex}.
 */
public final class PopularTermDiscardPolicy {

  private PopularTermDiscardPolicy() {}

  /** Returns the configured maximum fraction of rows a term may appear in before it is discarded. */
  public static double maxFractionIdsPerTerm(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig is null.");
    return namespaceConfig.readDoubleIndexParam(
        ConfigKeys.MAX_FRACTION_IDS_PER_TERM, ConfigKeys.DEFAULT_MAX_FRACTION_IDS_PER_TERM);
  }

  /** Returns whether any term may be discarded under the configured popularity threshold. */
  public static boolean doesDiscardPopularTerms(NamespaceConfig namespaceConfig) {
    return maxFractionIdsPerTerm(namespaceConfig) < 1.0;
  }

  /** Returns whether any term may be discarded under {@code maxFractionIdsPerTerm}. */
  public static boolean doesDiscardPopularTerms(double maxFractionIdsPerTerm) {
    return maxFractionIdsPerTerm < 1.0;
  }

  /**
   * Returns the terms a structure holding {@code rowNumToTermsAndValuesMap} discards as popular.
   */
  public static LongHashSet discardedTermsOf(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig is null.");
    Objects.requireNonNull(rowNumToTermsAndValuesMap, "rowNumToTermsAndValuesMap is null.");
    return discardedTermsOf(rowNumToTermsAndValuesMap, maxFractionIdsPerTerm(namespaceConfig));
  }

  /**
   * Identifies the high-popularity terms to discard. The structure sees the complete dataset, so
   * observed popularity is true popularity: a term is discarded when it occurs in more than
   * floor(numRows * maxFractionIdsPerTerm) rows.
   *
   * <p>Counted over the terms of each row, never over the keys the rows are indexed under, so a
   * frequent key is never discarded for being frequent. Where the keys are signatures they are
   * generated afterwards, from the rows these terms have been removed from.
   */
  public static LongHashSet discardedTermsOf(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      double maxFractionIdsPerTerm) {
    Objects.requireNonNull(rowNumToTermsAndValuesMap, "rowNumToTermsAndValuesMap is null.");
    LongIntHashMap numRowsByTerm = new LongIntHashMap();
    // A row counts once per distinct term, so a term repeated within one row stays one row.
    LongHashSet termsInRow = new LongHashSet();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      termsInRow.clear();
      for (int i = 0; i < entry.value.termsLength(); ++i) {
        long term = entry.value.getTerm(i);
        if (!termsInRow.add(term)) {
          continue;
        }
        int numRows = numRowsByTerm.containsKey(term) ? numRowsByTerm.get(term) + 1 : 1;
        numRowsByTerm.put(term, numRows);
      }
    }
    int maxNumRowsPerTerm =
        (int) Math.floor(rowNumToTermsAndValuesMap.size() * maxFractionIdsPerTerm);
    LongHashSet popularTerms = new LongHashSet();
    for (LongIntCursor entry : numRowsByTerm) {
      if (entry.value > maxNumRowsPerTerm) {
        popularTerms.add(entry.key);
      }
    }
    return popularTerms;
  }
}
