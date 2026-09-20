/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;

/**
 * Scores a query against every row of a dense matrix and returns the rows it keeps.
 *
 * <p>The rows it keeps rather than a dot product for every row. An implementation able to discard
 * the rows it will not keep therefore does so before returning, instead of returning as many values
 * as the matrix has rows for the caller to discard.
 */
interface MatrixDotProductScorer extends AutoCloseable {

  /** The rows this query keeps, in the order the bounded heap holds them. */
  List<RowNumAndSimilarity> selectRows(float[] queryValues, RowSelection selection);

  @Override
  default void close() {}
}
