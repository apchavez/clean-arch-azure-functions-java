package com.clinic.domain.entities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CountryISOTest {

  @Test
  void isSupported_nullValue_returnsFalse() {
    assertFalse(CountryISO.isSupported(null));
  }

  @Test
  void isSupported_blankValue_returnsFalse() {
    assertFalse(CountryISO.isSupported("   "));
  }

  @Test
  void isSupported_knownCode_returnsTrue() {
    assertTrue(CountryISO.isSupported("PE"));
    assertTrue(CountryISO.isSupported("CL"));
  }

  @Test
  void isSupported_unknownCode_returnsFalse() {
    assertFalse(CountryISO.isSupported("US"));
  }

  @Test
  void supportedValues_listsAllCountriesCommaSeparated() {
    assertEquals("PE,CL", CountryISO.supportedValues());
  }
}
