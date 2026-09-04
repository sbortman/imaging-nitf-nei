package org.codice.imaging.nitf.nei;

import java.util.Objects;

/** One parsed NEI record and its location in a NITF file. */
public final class NeiOccurrence {
    public enum Container {
        IMAGE_TRE,
        DATA_EXTENSION
    }

    private final Container container;
    private final int segmentIndex;
    private final NeiRecord record;

    public NeiOccurrence(final Container container, final int segmentIndex, final NeiRecord record) {
        this.container = Objects.requireNonNull(container, "container");
        this.segmentIndex = segmentIndex;
        this.record = Objects.requireNonNull(record, "record");
    }

    public Container getContainer() {
        return container;
    }

    /** One-based segment index, matching NITF display-level conventions. */
    public int getSegmentIndex() {
        return segmentIndex;
    }

    public NeiRecord getRecord() {
        return record;
    }
}

