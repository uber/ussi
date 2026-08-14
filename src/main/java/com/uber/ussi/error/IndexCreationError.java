/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

public class IndexCreationError extends RuntimeException {
  public IndexCreationError(String errorMessage) {
    super(errorMessage);
  }
}
