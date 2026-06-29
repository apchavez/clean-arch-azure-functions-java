package com.clinic.domain.entities;

/**
 * Supported countries for country-specific booking flows (PE / CL),
 * matching the AWS project's country-based processing.
 */
public enum CountryISO {
    PE,
    CL;

    public static boolean isSupported(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (CountryISO country : values()) {
            if (country.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static String supportedValues() {
        StringBuilder values = new StringBuilder();
        for (CountryISO country : values()) {
            if (!values.isEmpty()) {
                values.append(",");
            }
            values.append(country.name());
        }
        return values.toString();
    }
}
