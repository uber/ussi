package com.uber.ussi.entity.termsandvalues;

/** Test-only access to the trusted constructor for validating malformed internal records. */
public final class LongTermsAndValuesTestFactory {

  private LongTermsAndValuesTestFactory() {}

  public static LongTermsAndValues create(long[] terms, float[] values, double uniValue) {
    return new LongTermsAndValues(terms, values, uniValue);
  }
}
