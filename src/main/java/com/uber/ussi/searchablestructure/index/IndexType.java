/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.entity.termsandvalues.RecordType;
import java.util.EnumSet;
import java.util.Set;

/**
 * The index structures a namespace can be configured with, each paired with the record types it can
 * store.
 *
 * <p>A namespace names only the structure: the record type is the one the structure stores and the
 * configured comparator reads; see {@link #resolveRecordTypes}.
 */
public enum IndexType implements ConfigVocabulary {
  /**
   * Sequential scan that scores every row through the comparator, so it never reads a record's
   * layout itself and stores whichever type the comparator reads.
   */
  SCAN(RecordType.ORDER_AGNOSTIC_DENSE, RecordType.SEQUENCE, RecordType.ORDER_AGNOSTIC_SPARSE),

  /** Matrix scan over fixed-dimension dense vectors. */
  MATRIX(RecordType.ORDER_AGNOSTIC_DENSE),

  /**
   * Inverted lists keyed by the terms of the record itself: a sparse record's own terms carrying
   * its own values, or the distinct elements of a sequence carrying how often each occurs.
   */
  INVERTED_TERM(RecordType.SEQUENCE, RecordType.ORDER_AGNOSTIC_SPARSE),

  /**
   * Inverted lists keyed by similarity-preserving signatures, which only a comparator with a
   * configured signature generator can produce.
   */
  INVERTED_SIGNATURE(RecordType.ORDER_AGNOSTIC_SPARSE),

  /** Term-keyed lists for the short rows and signature-keyed lists for the long ones. */
  INVERTED_HYBRID(RecordType.ORDER_AGNOSTIC_SPARSE);

  private final Set<RecordType> storableRecordTypes;

  IndexType(RecordType... storableRecordTypes) {
    this.storableRecordTypes = Set.of(storableRecordTypes);
  }

  public Set<RecordType> getStorableRecordTypes() {
    return storableRecordTypes;
  }

  /**
   * Returns the record types this structure stores that the comparator also reads. An empty result
   * is a structure paired with a comparator that can read nothing it stores.
   */
  public Set<RecordType> resolveRecordTypes(Comparator comparator) {
    Set<RecordType> resolvedRecordTypes = EnumSet.noneOf(RecordType.class);
    for (RecordType recordType : storableRecordTypes) {
      if (comparator.getSupportedRecordTypes().contains(recordType)) {
        resolvedRecordTypes.add(recordType);
      }
    }
    return resolvedRecordTypes;
  }

  /** Returns whether this structure keeps the uni-sorted inverted lists the generators walk. */
  public boolean supportsCandidateGenerator() {
    return this == INVERTED_TERM || this == INVERTED_SIGNATURE || this == INVERTED_HYBRID;
  }

  /** Returns whether this structure's keys are signatures the comparator has to generate. */
  public boolean requiresSignatureSupport() {
    return this == INVERTED_SIGNATURE || this == INVERTED_HYBRID;
  }

  /**
   * Returns whether the conjunction a merge accumulates is a candidate's similarity rather than a
   * bound on it. It is only when the lists carry a record's own terms and values; a sequence's
   * elements are keyed without the order its similarity depends on, so they only bound it.
   */
  public boolean conjunctionDeterminesSimilarity(RecordType recordType) {
    return this == INVERTED_TERM && recordType == RecordType.ORDER_AGNOSTIC_SPARSE;
  }
}
