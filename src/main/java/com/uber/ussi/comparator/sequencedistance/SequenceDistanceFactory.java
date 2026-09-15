/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Objects;

/** Creates the edit distances supported by the sequence comparators. */
public final class SequenceDistanceFactory {
  /**
   * The edit distance a sequence comparator measures with. Each permits a different set of edits,
   * so the same pair of sequences has a different distance under each.
   */
  public enum SequenceDistanceType implements ConfigVocabulary {
    /**
     * Levenshtein plus transposition of two adjacent terms, so a pair of terms in the wrong
     * order costs one edit rather than two.
     */
    DAMERAU_LEVENSHTEIN,

    /**
     * Insertion and deletion of one term, and nothing else, complementing the longest common
     * subsequence (LCS). Rewriting a term costs both, so this is never below Levenshtein.
     */
    LCS,

    /** Insertion, deletion, and substitution of one term. */
    LEVENSHTEIN
  }

  private SequenceDistanceFactory() {}

  public static SequenceDistance createSequenceDistance(String sequenceDistanceType) {
    Objects.requireNonNull(sequenceDistanceType, "sequenceDistanceType");
    SequenceDistanceType type =
        ConfigVocabulary.fromParamValue(SequenceDistanceType.class, sequenceDistanceType);
    if (type == null) {
      throw new IllegalArgumentException(
          ConfigVocabulary.unsupported(
              ConfigKeys.SEQUENCE_DISTANCE_TYPE, sequenceDistanceType,
              SequenceDistanceType.class));
    }
    return createSequenceDistance(type);
  }

  public static SequenceDistance createSequenceDistance(SequenceDistanceType type) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case DAMERAU_LEVENSHTEIN -> new DamerauLevenshteinDistance();
      case LCS -> new LcsDistance();
      case LEVENSHTEIN -> new LevenshteinDistance();
    };
  }
}
