/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * An ordered sequence indexed by the multiset of its elements.
 *
 * <p>Edit distance depends on order, so shared elements only bound it: two sequences within edit
 * distance {@code d} have element multisets within L1 distance {@code l1BoundFactor * d}. The
 * multiset index generates candidates under that bound and the comparator verifies each survivor
 * against the ordered sequences.
 *
 * <p>Discarding a popular element from the multisets alone costs recall, because the bound stops
 * holding for the sequences that still carry it; see {@code popular_term_discard_scope}.
 */
final class SequenceIndexingStrategy implements RecordIndexingStrategy {

  @Override
  public LongTermsAndValues toIndexedRecord(
      LongTermsAndValues termsAndValues, Comparator comparator) {
    return termsAndValues.toElementMultiset(comparator);
  }

  /** A sequence carries its elements, in order and with repeats, in its terms and has no values. */
  @Override
  public void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    if (termsAndValues.valuesLength() != 0) {
      throw new IllegalArgumentException(
          String.format(
              "%s must have no values, because a sequence carries its elements, in order and with"
                  + " repeats, in its terms.",
              source));
    }
  }
}
