/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

/**
 * What a sparse index uses as the keys of its inverted lists.
 *
 * <p>This decides whether merge candidate generation can score a row from the conjunction it
 * accumulates while traversing the lists, or has to verify each candidate through the comparator.
 */
enum SparseKeyType {
  /**
   * The keys are the rows' terms and the lists carry the rows' values, so a candidate's conjunction
   * is its exact similarity and the merge needs no verification forward map.
   */
  EXACT_TERM(/* requiresSignatureSupport */ false),

  /**
   * The keys are the distinct elements of a row's sequence and the lists carry their counts, so a
   * candidate's conjunction bounds how far apart the two sequences can be but does not say how far
   * apart they are. The order the elements appear in decides that, and only the comparator sees it.
   */
  SEQUENCE_ELEMENT(/* requiresSignatureSupport */ false),

  /**
   * The keys are similarity-preserving signatures, so sharing a key says nothing about the values
   * behind it and every candidate's similarity has to be verified through the comparator.
   */
  SIGNATURE(/* requiresSignatureSupport */ true);

  private final boolean requiresSignatureSupport;

  SparseKeyType(boolean requiresSignatureSupport) {
    this.requiresSignatureSupport = requiresSignatureSupport;
  }

  boolean requiresSignatureSupport() {
    return requiresSignatureSupport;
  }

  boolean supportsConjunctionScoring() {
    return this == EXACT_TERM;
  }
}
