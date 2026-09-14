/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * A sparse record indexed by its own terms.
 *
 * <p>This is the type the inverted machinery is written against, so the indexed form is the record
 * itself and the validation is what that machinery assumes of it.
 */
final class OrderAgnosticSparseIndexingStrategy implements RecordIndexingStrategy {

  @Override
  public LongTermsAndValues toIndexedRecord(
      LongTermsAndValues termsAndValues, Comparator comparator) {
    return termsAndValues;
  }

  /**
   * Validates a record is sparse: one value per term, and terms in ascending order without
   * repeats. This is what the comparators that score two records by walking them in step require,
   * and what lets a record's own terms serve as inverted-list keys.
   */
  @Override
  public void validateRecordType(LongTermsAndValues termsAndValues, String source) {
    if (termsAndValues.termsLength() != termsAndValues.valuesLength()) {
      throw new IllegalArgumentException(
          String.format("%s must have equal non-empty terms and values lengths.", source));
    }
    for (int i = 1; i < termsAndValues.termsLength(); ++i) {
      if (termsAndValues.getTerm(i - 1) >= termsAndValues.getTerm(i)) {
        throw new IllegalArgumentException(
            String.format("%s terms must be sorted and distinct.", source));
      }
    }
  }
}
