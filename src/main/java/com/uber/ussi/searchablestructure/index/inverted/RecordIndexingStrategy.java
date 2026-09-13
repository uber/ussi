/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;

/**
 * How one record type presents itself to an inverted index.
 *
 * <p>An index's structure and the type of the records it stores vary independently. The structures
 * are the class hierarchy this interface is composed into, so one implementation here serves every
 * structure, and a record type the family gains is one new implementation rather than one new
 * index class per structure.
 */
interface RecordIndexingStrategy {

  /**
   * Returns the indexed form of a record: the form whose terms key the inverted lists, which is
   * sorted and distinct and carries one value per term whatever type the caller's record had.
   *
   * @param comparator the comparator the record's values are transformed by, which the record
   *     types deriving an indexed form need in order to report the same Uni value from it.
   */
  LongTermsAndValues toIndexedRecord(LongTermsAndValues termsAndValues, Comparator comparator);

  /**
   * Validates that a record has this type, throwing {@link IllegalArgumentException} when it does
   * not. Only the type's own requirement belongs here; the requirements every inverted index shares
   * are checked by {@link BaseInvertedIndex#validateRecord}.
   */
  void validateRecordType(LongTermsAndValues termsAndValues, String source);
}
