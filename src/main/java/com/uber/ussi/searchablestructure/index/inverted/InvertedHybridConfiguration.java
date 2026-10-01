/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

/**
 * Shared constants for hybrid inverted indexes that combine term-keyed and signature-keyed halves.
 */
public final class InvertedHybridConfiguration {

  /**
   * How many signatures stand in for one long row in the signature-keyed half. Drawn from
   * experiments trading recall against list length.
   */
  public static final int NUM_SIGNATURES_PER_ROW = 270;

  /**
   * The largest term count a row can have and still be keyed by its own terms in the hybrid index.
   * Above this count a row is keyed by signatures instead.
   *
   * <p>It equals {@link #NUM_SIGNATURES_PER_ROW} because that is where signatures stop being a
   * saving: a row with fewer terms would be replaced by more signatures than it had terms.
   */
  public static final int TERM_KEYING_CUTOFF = NUM_SIGNATURES_PER_ROW;

  private InvertedHybridConfiguration() {}
}
