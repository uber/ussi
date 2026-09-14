/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.IndexCreationError;

/** Factory pairing a record type with how an inverted index stores it. */
final class RecordIndexingStrategyFactory {
  private static final RecordIndexingStrategy ORDER_AGNOSTIC_SPARSE_STRATEGY =
      new OrderAgnosticSparseIndexingStrategy();
  private static final RecordIndexingStrategy SEQUENCE_STRATEGY = new SequenceIndexingStrategy();

  private RecordIndexingStrategyFactory() {}

  /**
   * Returns how {@code recordType} is indexed. The strategies hold no state, so every index of a
   * record type shares one.
   */
  static RecordIndexingStrategy createRecordIndexingStrategy(RecordType recordType) {
    return switch (recordType) {
      case ORDER_AGNOSTIC_SPARSE -> ORDER_AGNOSTIC_SPARSE_STRATEGY;
      case SEQUENCE -> SEQUENCE_STRATEGY;
      case ORDER_AGNOSTIC_DENSE ->
          throw new IndexCreationError(
              String.format(
                  "No inverted index stores %s records.", recordType.getDisplayName()));
    };
  }
}
