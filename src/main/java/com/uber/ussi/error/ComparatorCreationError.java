/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

public class ComparatorCreationError extends RuntimeException {
  public ComparatorCreationError(String errorMessage) {
    super(errorMessage);
  }
}
