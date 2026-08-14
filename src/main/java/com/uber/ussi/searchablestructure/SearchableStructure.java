/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import java.util.List;

/** Common searchable structure API shared by caches and future indexes. */
public interface SearchableStructure {

  boolean delete(long rowNum);

  LongObjectHashMap<LongTermsAndValues> getAll();

  List<RowNumAndSimilarity> getNearestNeighbors(
      int k, LongTermsAndValues record, MetaFilter metadataFilter);

  List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter);
}
