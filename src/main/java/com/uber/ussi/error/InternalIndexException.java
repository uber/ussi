/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

/**
 * A fault inside USSI that is not the caller's doing.
 *
 * <p>Thrown for an invariant the library violated, such as a missing generation for an index or a
 * scorer used after it was closed. A host mapping failures to status codes treats this as an
 * internal error.
 */
public class InternalIndexException extends RuntimeException {

  public InternalIndexException(String message) {
    super(message);
  }

  public InternalIndexException(String message, Throwable cause) {
    super(message, cause);
  }
}
