/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * An ordered sequence indexed by the multiset of its elements.
 *
 * <p>An edit distance depends on the order its elements appear in, so it cannot be read off the
 * elements a query and a row share. What those shared elements do give is a bound: two sequences
 * within edit distance {@code d} have element multisets within L1 distance {@code l1BoundFactor *
 * d} of each other, so a row sharing too few elements with the query, disregarding their order,
 * cannot be close enough in order either. Indexing the multiset is what lets the inverted
 * machinery generate candidates under that bound while the comparator still verifies each survivor
 * against the ordered sequences.
 *
 * <p>Discarding a popular element removes it from the indexed multisets, and under the default
 * discard scope from the sequences themselves as well, so the distances reported are then the
 * distances between the sequences that remain. Removing it from the multisets alone costs recall,
 * because the bound the lists are searched under stops holding for the sequences that still carry
 * it; see the {@code popular_term_discard_scope} index param.
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
