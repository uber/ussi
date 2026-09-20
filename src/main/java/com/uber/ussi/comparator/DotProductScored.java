/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

/**
 * A similarity that a dot product determines, which a structure scoring every row by one matrix
 * multiply needs.
 *
 * <p>A measure qualifies when a dot product and the two unilateral values determine it. The dot
 * product carries everything the two records share, so whatever else the measure needs has to come
 * from each record on its own, which is what its unilateral value is.
 *
 * <p>Not every measure can implement this. One that needs the values themselves, rather than a sum
 * over them, does not decompose this way: Jaccard and Ruzicka weigh each shared position against
 * the larger of the two values, which no sum over either record alone recovers.
 *
 * <p>An implementation is required to depend on its three arguments through the squared
 * Euclidean distance between the two records alone, which is {@code uniValue1 + uniValue2 - 2 *
 * dotProduct}, and not to rise as that distance rises. Two records at equal distance therefore
 * score equally, and the nearest record is the most similar one. A structure is free to find
 * the rows to return by that distance and to score only those, which is what a scorer computing
 * on hardware this process cannot read does, so a measure ordering by anything else is not
 * served by one.
 */
public interface DotProductScored {

  /**
   * Returns the normalized similarity of two records whose dot product is {@code dotProduct} and
   * whose unilateral values are {@code uniValue1} and {@code uniValue2}.
   */
  double similarityFromDotProduct(double dotProduct, double uniValue1, double uniValue2);
}
