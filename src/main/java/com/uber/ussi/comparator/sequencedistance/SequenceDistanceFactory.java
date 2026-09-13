/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

import java.util.Locale;
import java.util.Objects;

/** Creates the edit distances supported by the sequence comparators. */
public final class SequenceDistanceFactory {
  /**
   * The edit distance a sequence comparator measures with, which is what the generalized
   * Levenshtein distance generalizes over. Each permits a different set of edits, so the same pair
   * of sequences has a different distance under each.
   */
  public enum SequenceDistanceType {
    /**
     * Levenshtein plus transposition of two adjacent elements, so a pair of elements in the wrong
     * order costs one edit rather than two.
     */
    DAMERAU_LEVENSHTEIN,

    /**
     * The distance complementing the longest common subsequence (LCS): insertion and deletion of
     * one element, and nothing else. Rewriting an element costs a deletion and an insertion, so an
     * LCS distance is never below the Levenshtein distance over the same pair.
     */
    LCS,

    /** Insertion, deletion, and substitution of one element. */
    LEVENSHTEIN
  }

  private SequenceDistanceFactory() {}

  public static SequenceDistance createSequenceDistance(String sequenceDistanceType) {
    Objects.requireNonNull(sequenceDistanceType, "sequenceDistanceType");
    SequenceDistanceType type;
    try {
      type = SequenceDistanceType.valueOf(sequenceDistanceType.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("Unsupported sequence distance type (%s).", sequenceDistanceType), e);
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
