/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * A sparse record indexed by its own terms.
 *
 * <p>The inverted machinery is written against this type, so the indexed form is the record itself.
 */
final class OrderAgnosticSparseIndexingStrategy implements RecordIndexingStrategy {

  @Override
  public LongTermsAndValues toIndexedRecord(
      LongTermsAndValues termsAndValues, Comparator comparator) {
    return termsAndValues;
  }

  /**
   * Validates a record is sparse: one value per term, terms ascending without repeats. Comparators
   * that walk two records in step require this, and it lets a record's terms serve as keys.
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
