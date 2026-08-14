/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

public class ArraysSizeMismatchError extends RuntimeException {
  public ArraysSizeMismatchError(String errorMessage) {
    super(errorMessage);
  }
}
