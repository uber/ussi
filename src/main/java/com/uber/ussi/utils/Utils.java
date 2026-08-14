/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.utils;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

public final class Utils {

  private Utils() {}

  /** Checks if the JVM is running on macOS. */
  public static boolean isRunningOnMacOs() {
    String osName = System.getProperty("os.name").toLowerCase();
    return osName.contains("mac");
  }

  /** Checks if the JVM is running on Linux. */
  public static boolean isRunningOnLinux() {
    String osName = System.getProperty("os.name").toLowerCase();
    return osName.contains("linux");
  }

  /** Checks if the JVM is running on ARM architecture. */
  public static boolean isRunningOnArm() {
    String osArchitecture = System.getProperty("os.arch").toLowerCase();
    return osArchitecture.contains("arm") || osArchitecture.contains("aarch64");
  }

  /** Checks if the JVM is running on x86 architecture. */
  public static boolean isRunningOnX86() {
    String osArchitecture = System.getProperty("os.arch").toLowerCase();
    return osArchitecture.contains("x86") || osArchitecture.contains("amd64");
  }

  public static byte[] doubleToByteArray(double value) {
    byte[] bytes = new byte[Long.BYTES];
    ByteBuffer.wrap(bytes).putDouble(value);
    return bytes;
  }

  public static long byteArrayToLong(byte[] bytes) {
    if (bytes.length != Long.BYTES) {
      throw new IllegalArgumentException(
          String.format("These bytes (%s) cannot be converted to long.", Arrays.toString(bytes)));
    }
    return ByteBuffer.wrap(bytes).getLong();
  }

  /** When fingerprinting a double, ignores the 8 lest significant digits. */
  public static long longHashCode(double d) {
    byte[] bytes = doubleToByteArray(d);
    bytes[bytes.length - 1] = 0;
    return byteArrayToLong(bytes);
  }

  /**
   * Combines two longs into a single fingerprint using Guava's FarmHash. The combination is
   * order-dependent (unlike XOR), so {@code longHashCode(k, v) != longHashCode(v, k)} and {@code
   * longHashCode(k, k) != 0}. This prevents key/value-swapped metadata pairs from colliding.
   */
  public static long longHashCode(long k, long v) {
    return Hashing.farmHashFingerprint64().newHasher().putLong(k).putLong(v).hash().asLong();
  }

  /** Uses Guava's FarmHash implementation to fingerprint a String. */
  public static long farmHashFingerprint64(String str) {
    return Hashing.farmHashFingerprint64()
        .hashString(str.trim(), Charset.defaultCharset())
        .asLong();
  }

  /**
   * Returns a pseudo random number generator seeded by t. Done by converting t to a String, str,
   * and calling Guava FarmHash on str. Hence, a consistent String value should be produced every
   * time toString() is invoked on any T instance.
   */
  public static <T> long longHashCode(T t) {
    if (t instanceof Long) {
      return (long) t;
    } else if (t instanceof Integer) {
      return ((Integer) t).longValue();
    } else if (t instanceof Double) {
      return longHashCode((double) t);
    } else if (t instanceof String) {
      return farmHashFingerprint64((String) t);
    } else if (t instanceof Short) {
      return ((Short) t).longValue();
    } else if (t instanceof Float) {
      return longHashCode(((Float) t).doubleValue());
    } else if (t instanceof Character) {
      return farmHashFingerprint64(((Character) t).toString());
    } else if (t instanceof Byte) {
      return ((Byte) t).longValue();
    } else if (t instanceof Boolean) {
      return farmHashFingerprint64(((Boolean) t).toString());
    } else {
      throw new RuntimeException(
          String.format(
              "Cannot find the longHashCode for object %s of type %s",
              t.toString(), t.getClass().getName()));
    }
  }
}
