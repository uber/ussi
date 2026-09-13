/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The index structures a namespace can be configured with, each paired with the record types it can
 * store.
 *
 * <p>A structure and a record type vary independently: the structure decides what the index is
 * keyed by and how it generates candidates, and the record type decides how a record is laid out. A
 * namespace names only the structure, because a comparator publishes the record types it can read
 * and an index stores the type the two have in common; see {@link #resolveRecordTypes}.
 */
public enum IndexType {
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
   * Inverted lists keyed by similarity-preserving signatures. Only a comparator with a configured
   * signature generator can produce them, which today means a comparator reading sparse records.
   */
  INVERTED_SIGNATURE(RecordType.ORDER_AGNOSTIC_SPARSE),

  /** Term-keyed lists for the short rows and signature-keyed lists for the long ones. */
  INVERTED_HYBRID(RecordType.ORDER_AGNOSTIC_SPARSE);

  private final Set<RecordType> storableRecordTypes;

  IndexType(RecordType... storableRecordTypes) {
    this.storableRecordTypes = Set.of(storableRecordTypes);
  }

  /** Returns the structure of {@code paramValue}, or null if no structure has that name. */
  @Nullable
  public static IndexType fromParamValue(String paramValue) {
    String normalizedParamValue = paramValue.trim().toLowerCase(Locale.ROOT);
    for (IndexType indexType : values()) {
      if (indexType.getParamValue().equals(normalizedParamValue)) {
        return indexType;
      }
    }
    return null;
  }

  public String getParamValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public Set<RecordType> getStorableRecordTypes() {
    return storableRecordTypes;
  }

  /**
   * Returns the record types this structure can store that the comparator can also read, which is
   * the one type an index configured this way holds. An empty result is a config that pairs a
   * structure with a comparator that cannot read anything it stores.
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
   * Returns whether the conjunction a merge accumulates while walking the inverted lists is a
   * candidate's similarity rather than a bound on it. It is when the lists are keyed by a record's
   * own terms and carry its own values, which takes both a term-keyed structure and a record type
   * whose terms determine its similarity. A sequence's elements, keyed without the order the
   * similarity depends on, only bound it.
   */
  public boolean conjunctionDeterminesSimilarity(RecordType recordType) {
    return this == INVERTED_TERM && recordType == RecordType.ORDER_AGNOSTIC_SPARSE;
  }
}
