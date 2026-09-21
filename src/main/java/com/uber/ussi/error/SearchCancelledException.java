/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.error;

/**
 * A search cancelled while it was waiting or running.
 *
 * <p>USSI blocks in three places: the admission semaphore, the batch latch, and the work-unit
 * futures. A host cancels a search by interrupting the thread it runs on, which USSI treats as a
 * cancellation rather than a failure. The interrupt flag is restored before this is thrown, so a
 * host that catches it may continue on the thread.
 *
 * <p>A search cancelled while queued for admission took no permit and ran nothing. A search
 * cancelled while waiting on a batch another thread was running leaves the batch to finish for the
 * other queries in it. A search cancelled while its work units were outstanding waits for them to
 * finish before throwing, since returning earlier would leave a work unit reading a structure that
 * the read lock its caller held is no longer protecting.
 */
public class SearchCancelledException extends RuntimeException {

  public SearchCancelledException(String message) {
    super(message);
  }

  public SearchCancelledException(String message, Throwable cause) {
    super(message, cause);
  }
}
