package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigViolationsTest {

  private static final BlankCase[] BLANK_CASES = {
    new BlankCase(null, false),
    new BlankCase("", false),
    new BlankCase("   ", false),
    new BlankCase("ok", true),
  };

  private static final ClosedRangeCase[] CLOSED_RANGE_CASES = {
    new ClosedRangeCase("unset", null, true),
    new ClosedRangeCase("min", "0.0", true),
    new ClosedRangeCase("mid", "0.5", true),
    new ClosedRangeCase("max", "1.0", true),
    new ClosedRangeCase("below", "-0.1", false),
    new ClosedRangeCase("above", "1.1", false),
    new ClosedRangeCase("not-a-number", "abc", false),
  };

  private static final OpenRangeCase[] OPEN_RANGE_CASES = {
    new OpenRangeCase("unset", null, true),
    new OpenRangeCase("min-excluded", "0.0", false),
    new OpenRangeCase("just-above-min", "0.0001", true),
    new OpenRangeCase("max", "1.0", true),
    new OpenRangeCase("above", "1.1", false),
  };

  private static final IntegerCheckCase[] INTEGER_CHECK_CASES = {
    new IntegerCheckCase("checkPositive", ConfigViolations::checkPositive, 1, true),
    new IntegerCheckCase("checkPositive", ConfigViolations::checkPositive, 0, false),
    new IntegerCheckCase("checkNonNegative", ConfigViolations::checkNonNegative, 0, true),
    new IntegerCheckCase("checkNonNegative", ConfigViolations::checkNonNegative, -1, false),
  };

  private static final FormatCase[] FORMAT_CASES = {
    new FormatCase(List.of("only one"), "only one"),
    new FormatCase(
        List.of("first", "second"),
        "NamespaceConfig has 2 violations:\n  - first\n  - second"),
  };

  @Test
  void checkNonBlankCases() {
    for (BlankCase testCase : BLANK_CASES) {
      List<String> violations = new ArrayList<>();
      ConfigViolations.checkNonBlank(violations, "field", testCase.value);
      assertEquals(testCase.valid, violations.isEmpty(), "value=" + testCase.value);
    }
  }

  @Test
  void checkDoubleInRangeCases() {
    for (ClosedRangeCase testCase : CLOSED_RANGE_CASES) {
      List<String> violations = new ArrayList<>();
      ConfigViolations.checkDoubleInRange(violations, "ratio", testCase.rawValue, 0.0, 1.0);
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  @Test
  void checkDoubleAboveMinInRangeCases() {
    for (OpenRangeCase testCase : OPEN_RANGE_CASES) {
      List<String> violations = new ArrayList<>();
      ConfigViolations.checkDoubleAboveMinInRange(
          violations, "fraction", testCase.rawValue, 0.0, 1.0);
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  @Test
  void integerCheckCases() {
    for (IntegerCheckCase testCase : INTEGER_CHECK_CASES) {
      List<String> violations = new ArrayList<>();
      testCase.check.apply(violations, "count", testCase.value);
      assertEquals(testCase.valid, violations.isEmpty(), testCase.label());
    }
  }

  @Test
  void formatCases() {
    for (FormatCase testCase : FORMAT_CASES) {
      assertEquals(testCase.expected, ConfigViolations.format(testCase.violations));
    }
  }

  @Test
  void invalidDoubleReportsTheExpectedRangeInTheMessage() {
    List<String> violations = new ArrayList<>();
    ConfigViolations.checkDoubleInRange(violations, "ratio", "not-a-double", 0.0, 1.0);
    assertEquals(1, violations.size());
    assertTrue(violations.get(0).contains("[0.0, 1.0]"));
  }

  private record BlankCase(String value, boolean valid) {}

  private record ClosedRangeCase(String name, String rawValue, boolean valid) {}

  private record OpenRangeCase(String name, String rawValue, boolean valid) {}

  private record FormatCase(List<String> violations, String expected) {}

  @FunctionalInterface
  private interface IntegerCheck {
    void apply(List<String> violations, String name, int value);
  }

  private record IntegerCheckCase(String checkName, IntegerCheck check, int value, boolean valid) {
    String label() {
      return checkName + "(" + value + ")";
    }
  }
}
