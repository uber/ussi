/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

public class ComparatorNormalizerCreationError extends RuntimeException {
  public ComparatorNormalizerCreationError(String errorMessage) {
    super(errorMessage);
  }
}
