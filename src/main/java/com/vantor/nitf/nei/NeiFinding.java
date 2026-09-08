package com.vantor.nitf.nei;

import java.util.Objects;

/**
 * One validation finding: a specific, named defect at a specific place in a NITF.
 *
 * <p>A finding carries a stable {@code code} so downstream tooling can suppress or
 * track an individual rule without matching on prose, and it always reports what
 * was actually found alongside what was expected. A rule that cannot state both
 * should be reported as {@link Severity#INFO} rather than asserting a defect.
 */
public final class NeiFinding {
    /** How much a finding matters. Only ERROR sets a non-zero exit status. */
    public enum Severity {
        /** Violates a standard or an internal invariant: the file is wrong. */
        ERROR,
        /** Legal but suspect, or non-conformant in a way consumers tolerate. */
        WARN,
        /** Context. Never a defect; carried so a report is self-explaining. */
        INFO
    }

    private final Severity severity;
    private final String code;
    private final String location;
    private final String field;
    private final String actual;
    private final String expected;
    private final String message;

    public NeiFinding(final Severity severity, final String code, final String location,
            final String field, final String actual, final String expected,
            final String message) {
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = Objects.requireNonNull(code, "code");
        this.location = location == null ? "" : location;
        this.field = field == null ? "" : field;
        this.actual = actual == null ? "" : actual;
        this.expected = expected == null ? "" : expected;
        this.message = message == null ? "" : message;
    }

    public static NeiFinding error(final String code, final String location, final String field,
            final String actual, final String expected, final String message) {
        return new NeiFinding(Severity.ERROR, code, location, field, actual, expected, message);
    }

    public static NeiFinding warn(final String code, final String location, final String field,
            final String actual, final String expected, final String message) {
        return new NeiFinding(Severity.WARN, code, location, field, actual, expected, message);
    }

    public static NeiFinding info(final String code, final String location, final String field,
            final String actual, final String message) {
        return new NeiFinding(Severity.INFO, code, location, field, actual, "", message);
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getLocation() {
        return location;
    }

    public String getField() {
        return field;
    }

    public String getActual() {
        return actual;
    }

    public String getExpected() {
        return expected;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append(severity).append(' ').append(code).append(' ').append(location);
        if (!field.isEmpty()) {
            out.append(' ').append(field);
        }
        if (!actual.isEmpty() || !expected.isEmpty()) {
            out.append(": found ").append(quote(actual));
            if (!expected.isEmpty()) {
                out.append(", expected ").append(expected);
            }
        }
        if (!message.isEmpty()) {
            out.append(" -- ").append(message);
        }
        return out.toString();
    }

    static String quote(final String value) {
        return value.isEmpty() ? "(empty)" : "'" + value + "'";
    }
}
