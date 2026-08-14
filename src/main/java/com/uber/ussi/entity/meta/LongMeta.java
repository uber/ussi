/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.meta;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.utils.Utils;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.validation.constraints.NotNull;
import lombok.Getter;

/**
 * Internal metadata representation backed by hashed {@code (metadataKey, filterKey)} pairs.
 *
 * <p>The summed pair hashes form a compact dictionary key for identical metadata objects. The
 * metadata filtering module still verifies equality after lookup, so hash collisions are detected
 * rather than treated as equivalent metadata.
 */
@Getter
public class LongMeta {

  private static final LongMeta EMPTY = new LongMeta();
  private final LongHashSet longMetadata;
  private final long longHashCode;

  public LongMeta(@NotNull Map<String, String> meta, boolean requireLongKeysAndValues) {
    this.longMetadata = new LongHashSet();
    long mutableLongHashCode = 0;
    for (Map.Entry<String, String> kv : meta.entrySet()) {
      String key = kv.getKey();
      String value = kv.getValue();
      long longKey;
      long longValue;
      if (requireLongKeysAndValues) {
        longKey = Long.parseLong(key);
        longValue = Long.parseLong(value);
      } else {
        longKey = longHashCode(key);
        longValue = longHashCode(value);
      }
      long metadataAndFilterKey = longHashCode(longKey, longValue);
      if (longMetadata.add(metadataAndFilterKey)) {
        mutableLongHashCode += metadataAndFilterKey;
      }
    }
    this.longHashCode = mutableLongHashCode;
  }

  public LongMeta() {
    this(new HashMap<>(), /* requireLongKeysAndValues */ true);
  }

  public static LongMeta empty() {
    return EMPTY;
  }

  public static long longHashCode(String stringValue) {
    return Utils.longHashCode(stringValue.toLowerCase(Locale.ROOT));
  }

  public static long longHashCode(long k, long v) {
    return Utils.longHashCode(k, v);
  }

  public static long longHashCode(String k, String v) {
    return longHashCode(longHashCode(k), longHashCode(v));
  }

  public boolean intersects(LongHashSet metadataFilterSet) {
    for (LongCursor cursor : metadataFilterSet) {
      if (longMetadata.contains(cursor.value)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return "LongMeta{longMetadata=" + longMetadata + '}';
  }

  public long longHashCode() {
    return longHashCode;
  }

  @Override
  public int hashCode() {
    return longMetadata.hashCode();
  }

  @Override
  public boolean equals(final Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof LongMeta)) {
      return false;
    }
    LongMeta other = (LongMeta) o;
    return longHashCode() == other.longHashCode() && longMetadata.equals(other.longMetadata);
  }
}
