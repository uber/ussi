/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.sequencedistance;

/**
 * The number of insertions and deletions of single elements needed to turn one sequence into the
 * other, the edit distance complementing the longest common subsequence.
 *
 * <p>Without substitution, rewriting an element costs a deletion and an insertion, so this is never
 * below the Levenshtein distance. In exchange each edit moves exactly one element into or out of
 * one multiset, which halves the L1 bound and makes candidate generation more selective.
 */
final class LcsDistance extends SequenceDistance {

  LcsDistance() {
    super(/* l1BoundFactor */ 1.0, /* allowsSubstitution */ false, /* allowsTransposition */ false);
  }
}
