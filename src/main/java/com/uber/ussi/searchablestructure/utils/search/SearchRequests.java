/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.search;

import com.uber.ussi.config.NamespaceConfig;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Shared validation and result limits for cache and index search entry points. */
public final class SearchRequests {

  private SearchRequests() {}

  /** Throws when {@code k} is not positive. */
  public static void requirePositiveK(int k) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
  }

  /** Throws when {@code minSimilarity} is outside {@code [0.0, 1.0]}. */
  public static void requireMinSimilarityInUnitInterval(float minSimilarity) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
  }

  /**
   * Returns the number of rows a k-nearest-neighbor search may return, capped by the namespace
   * limit.
   */
  public static int nearestNeighborResultLimit(NamespaceConfig namespaceConfig, int k) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig is null.");
    requirePositiveK(k);
    return Math.min(k, namespaceConfig.getMaxNumSimilarities());
  }

  /** Returns the number of rows a minimum-similarity search may return. */
  public static int similarSearchResultLimit(NamespaceConfig namespaceConfig) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig is null.");
    return namespaceConfig.getMaxNumSimilarities();
  }

  /**
   * Returns an empty result list when {@code maxResults} is zero or the structure holds no rows.
   */
  public static <T> List<T> emptyResultsIfNothingToSearch(int maxResults, int numRows) {
    if (maxResults == 0 || numRows == 0) {
      return Collections.emptyList();
    }
    return null;
  }
}
