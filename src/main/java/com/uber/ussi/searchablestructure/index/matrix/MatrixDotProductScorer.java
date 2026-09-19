/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;

/**
 * Scores a query against every row of a dense matrix and returns the rows it keeps.
 *
 * <p>The rows rather than a product per row, because an implementation computing its products
 * where the host cannot read them would otherwise have to copy one product per row back for
 * every query, which is the largest transfer a dense query makes and grows with the queries
 * scored together.
 */
interface MatrixDotProductScorer extends AutoCloseable {

  /** The rows this query keeps, best first by the heap's order. */
  List<RowNumAndSimilarity> selectRows(float[] queryValues, RowSelection selection);

  @Override
  default void close() {}
}
