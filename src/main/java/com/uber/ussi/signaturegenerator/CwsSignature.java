/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.utils.Utils;

/** A term and sampled weight produced by a consistent weighted sampling algorithm. */
final class CwsSignature {
  private final long term;
  private final double weight;

  CwsSignature(long term, double weight) {
    this.term = term;
    this.weight = weight;
  }

  long longHashCode() {
    return term ^ Utils.longHashCode(weight);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CwsSignature)) {
      return false;
    }
    CwsSignature that = (CwsSignature) o;
    return term == that.term && Double.compare(weight, that.weight) == 0;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(longHashCode());
  }

  @Override
  public String toString() {
    return "CwsSignature{" + "term=" + term + ", weight=" + weight + '}';
  }
}
