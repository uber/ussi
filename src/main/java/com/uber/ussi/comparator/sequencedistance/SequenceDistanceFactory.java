/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

import java.util.Locale;
import java.util.Objects;

/** Creates the edit distances supported by the sequence comparators. */
public final class SequenceDistanceFactory {
  public enum SequenceDistanceType {
    DAMERAU_LEVENSHTEIN,
    LCS,
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
