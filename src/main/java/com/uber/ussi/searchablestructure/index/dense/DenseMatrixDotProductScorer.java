/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.dense;

/** Computes matrix-vector dot products for dense exact search. */
interface DenseMatrixDotProductScorer extends AutoCloseable {

  void score(float[] queryValues, float[] dotProducts);

  @Override
  default void close() {}
}
