/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * Inverted index over the element multisets of ordered sequences.
 *
 * <p>An edit distance depends on the order its elements appear in, so it cannot be read off the
 * elements a query and a row share. What those shared elements do give is a bound: two sequences
 * within edit distance {@code d} have element multisets within L1 distance {@code l1BoundFactor *
 * d} of each other, so a row sharing too few elements with the query, disregarding their order,
 * cannot be close enough in order either. This index generates candidates from the multisets and
 * has the comparator verify each survivor against the ordered sequences, which is the separation
 * {@link #toIndexedRecord} expresses.
 *
 * <p>Discarding a popular element removes it from the multisets keyed into the inverted lists, and
 * under the default discard scope from the sequences themselves as well, so the distances reported
 * are then the distances between the sequences that remain. Removing it from the multisets alone
 * costs recall, because the bound the lists are searched under stops holding for the sequences that
 * still carry it; see the {@code popular_term_discard_scope} index param.
 */
public final class SequenceIndex extends BaseTermKeyedIndex {

  public SequenceIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    super(
        namespaceConfig,
        rowNumToTermsAndValuesMap,
        rowNumToMetaMap,
        SparseKeyType.SEQUENCE_ELEMENT);
  }

  /** A sequence is indexed by the multiset of its elements, and scored in the order it arrived. */
  @Override
  protected LongTermsAndValues toIndexedRecord(LongTermsAndValues termsAndValues) {
    return termsAndValues.toElementMultiset(comparator);
  }

  /** A sequence carries its elements, in order and with repeats, in its terms and has no values. */
  @Override
  protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    if (termsAndValues.valuesLength() != 0) {
      throw new IllegalArgumentException(
          String.format(
              "%s must have no values, because a sequence carries its elements, in order and with"
                  + " repeats, in its terms.",
              source));
    }
  }
}
