/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.meta;

import com.carrotsearch.hppc.LongHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.validation.constraints.NotNull;
import lombok.Getter;

/**
 * Public metadata filter representation.
 *
 * <p>Values for the same metadata key are ORed together; different metadata keys are ANDed. Keys
 * and values are lowercased and matched exactly after encoding to longs.
 */
@Getter
public class MetaFilter {

  private static final List<LongHashSet> EMPTY_LONG_METADATA_FILTER = Collections.emptyList();
  private static final Map<String, List<String>> EMPTY_META_FILTER_KEY_MAP = Collections.emptyMap();
  private static final MetaFilter EMPTY = new MetaFilter();
  private final List<LongHashSet> longMetadataFilter;
  private final Map<String, List<String>> metaFilterKeysMap;

  public MetaFilter(@NotNull Map<String, List<String>> metaFilterMap) {
    if (metaFilterMap.isEmpty()) {
      this.longMetadataFilter = EMPTY_LONG_METADATA_FILTER;
      this.metaFilterKeysMap = EMPTY_META_FILTER_KEY_MAP;
      return;
    }
    this.longMetadataFilter = new ArrayList<>();
    this.metaFilterKeysMap = new HashMap<>();
    for (Map.Entry<String, List<String>> kv : metaFilterMap.entrySet()) {
      String key = kv.getKey().toLowerCase(Locale.ROOT);
      long longKey = LongMeta.longHashCode(key);
      LongHashSet longMetadataFilterSet = new LongHashSet();
      List<String> filterKeys = new ArrayList<>();
      for (String value : kv.getValue()) {
        String valueString = value.toLowerCase(Locale.ROOT);
        longMetadataFilterSet.add(
            LongMeta.longHashCode(longKey, LongMeta.longHashCode(valueString)));
        filterKeys.add(valueString);
      }
      this.longMetadataFilter.add(longMetadataFilterSet);
      this.metaFilterKeysMap.put(key, filterKeys);
    }
  }

  public MetaFilter() {
    this(Collections.emptyMap());
  }

  public static MetaFilter empty() {
    return EMPTY;
  }

  public boolean isEmpty() {
    return longMetadataFilter.isEmpty();
  }

  public boolean doesMatch(@NotNull LongMeta meta) {
    for (LongHashSet longMetadataFilterSet : longMetadataFilter) {
      if (!meta.intersects(longMetadataFilterSet)) {
        return false;
      }
    }
    return true;
  }

  public Map<String, List<String>> getValuesForEachKey(Set<String> keySet) {
    Map<String, List<String>> keyToValueMap = new HashMap<>();
    for (String key : keySet) {
      String normalizedKey = key.toLowerCase(Locale.ROOT);
      if (metaFilterKeysMap.containsKey(normalizedKey)) {
        keyToValueMap.put(normalizedKey, metaFilterKeysMap.get(normalizedKey));
      } else {
        throw new NoSuchElementException("Key " + key + " not found in metaFilterKeysMap.");
      }
    }
    return keyToValueMap;
  }
}
