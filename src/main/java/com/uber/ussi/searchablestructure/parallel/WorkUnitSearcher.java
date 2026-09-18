/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.parallel;

import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import java.util.List;

/**
 * Searches one work unit of a divided search and returns the rows it keeps.
 *
 * <p>A work unit is a shard of a structure or a range of its rows. The minimum similarity is shared
 * with the other work units of the same search, so each prunes by what any of them has proved.
 */
@FunctionalInterface
public interface WorkUnitSearcher {

  List<RowNumAndSimilarity> search(int workUnitNumber, SharedMinSimilarity sharedMinSimilarity);
}
