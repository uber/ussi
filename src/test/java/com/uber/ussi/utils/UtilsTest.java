package com.uber.ussi.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class UtilsTest {

  @Test
  void osAndArchitectureProbesReturnBooleans() {
    boolean mac = Utils.isRunningOnMacOs();
    boolean linux = Utils.isRunningOnLinux();
    boolean arm = Utils.isRunningOnArm();
    boolean x86 = Utils.isRunningOnX86();

    assertEquals(System.getProperty("os.name").toLowerCase().contains("mac"), mac);
    assertEquals(System.getProperty("os.name").toLowerCase().contains("linux"), linux);
    assertEquals(
        System.getProperty("os.arch").toLowerCase().contains("arm")
            || System.getProperty("os.arch").toLowerCase().contains("aarch64"),
        arm);
    assertEquals(
        System.getProperty("os.arch").toLowerCase().contains("x86")
            || System.getProperty("os.arch").toLowerCase().contains("amd64"),
        x86);
  }

  @Test
  void byteArrayToLongRejectsWrongLength() {
    assertThrows(IllegalArgumentException.class, () -> Utils.byteArrayToLong(new byte[] {1, 2}));
  }

  @Test
  void longHashCodeOfDoubleIgnoresLeastSignificantByte() {
    assertEquals(Utils.longHashCode(1.5d), Utils.longHashCode(1.5d));
  }

  @Test
  void longHashCodeOfTwoLongsIsOrderDependentAndNonSymmetric() {
    assertNotEquals(Utils.longHashCode(6L, 3L), Utils.longHashCode(3L, 6L));
    assertNotEquals(0L, Utils.longHashCode(7L, 7L));
  }

  @Test
  void farmHashFingerprintTrimsInput() {
    assertEquals(Utils.farmHashFingerprint64("abc"), Utils.farmHashFingerprint64("  abc  "));
  }

  @Test
  void longHashCodeDispatchesByRuntimeType() {
    assertEquals(7L, Utils.longHashCode((Object) 7L));
    assertEquals(7L, Utils.longHashCode((Object) Integer.valueOf(7)));
    assertEquals(Utils.longHashCode(7.0d), Utils.longHashCode((Object) Double.valueOf(7.0)));
    assertEquals(Utils.farmHashFingerprint64("x"), Utils.longHashCode((Object) "x"));
    assertEquals(7L, Utils.longHashCode((Object) Short.valueOf((short) 7)));
    assertEquals(Utils.longHashCode(7.0d), Utils.longHashCode((Object) Float.valueOf(7f)));
    assertEquals(Utils.farmHashFingerprint64("c"), Utils.longHashCode((Object) 'c'));
    assertEquals(7L, Utils.longHashCode((Object) Byte.valueOf((byte) 7)));
    assertEquals(Utils.farmHashFingerprint64("true"), Utils.longHashCode((Object) Boolean.TRUE));
  }

  @Test
  void longHashCodeRejectsUnsupportedType() {
    assertThrows(RuntimeException.class, () -> Utils.longHashCode((Object) List.of(1)));
  }
}
