package org.codice.imaging.nitf.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed records plus any extension-level layout failures encountered during a scan. */
public final class NeiScanReport {
    private final List<NeiOccurrence> records = new ArrayList<>();
    private final List<NeiParseFailure> failures = new ArrayList<>();

    void add(final NeiOccurrence occurrence) {
        records.add(occurrence);
    }

    void fail(final NeiOccurrence.Container container, final int index,
            final String type, final NeiFormatException failure) {
        failures.add(new NeiParseFailure(container, index, type, failure.getMessage()));
    }

    public List<NeiOccurrence> getRecords() {
        return Collections.unmodifiableList(records);
    }

    public List<NeiParseFailure> getFailures() {
        return Collections.unmodifiableList(failures);
    }
}

