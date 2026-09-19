/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/**
 * What one query asks of the rows: the query's own unilateral value, the similarity a row must
 * reach, and how many rows to keep.
 *
 * <p>Given to whatever performs the multiply, not only to whatever reads its products, so that an
 * implementation able to choose rows where it computed them has everything it needs to do so.
 */
final class RowSelection {

  private final double queryUniValue;
  private final float minSimilarity;
  private final int maxNumRows;

  RowSelection(double queryUniValue, float minSimilarity, int maxNumRows) {
    this.queryUniValue = queryUniValue;
    this.minSimilarity = minSimilarity;
    this.maxNumRows = maxNumRows;
  }

  double getQueryUniValue() {
    return queryUniValue;
  }

  float getMinSimilarity() {
    return minSimilarity;
  }

  int getMaxNumRows() {
    return maxNumRows;
  }
}
