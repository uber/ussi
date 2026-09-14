/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * How one record type presents itself to an inverted index.
 *
 * <p>Structure and record type vary independently: the structures are the class hierarchy this
 * interface is composed into, so one implementation here serves every structure.
 */
interface RecordIndexingStrategy {

  /**
   * Returns the indexed form of a record: sorted distinct terms with one value per term, whatever
   * type the caller's record had. Derived forms transform their values through {@code comparator}
   * so that the indexed form reports the same Uni value.
   */
  LongTermsAndValues toIndexedRecord(LongTermsAndValues termsAndValues, Comparator comparator);

  /**
   * Validates that a record has this type, throwing {@link IllegalArgumentException} when it does
   * not. The requirements every inverted index shares are checked by {@link
   * BaseInvertedIndex#validateRecord}.
   */
  void validateRecordType(LongTermsAndValues termsAndValues, String source);
}
