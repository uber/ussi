/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

/**
 * A query a host submitted that uSSI rejected as malformed.
 *
 * <p>Thrown for a {@code k} that is not positive, a {@code minSimilarity} outside {@code [0.0,
 * 1.0]}, a record whose shape does not match the namespace, and a configuration that fails
 * validation at {@link com.uber.ussi.NearestNeighborSearchIndex#create create}. A host mapping
 * failures to status codes treats this as a client error.
 */
public class InvalidQueryException extends RuntimeException {

  public InvalidQueryException(String message) {
    super(message);
  }

  public InvalidQueryException(String message, Throwable cause) {
    super(message, cause);
  }
}
