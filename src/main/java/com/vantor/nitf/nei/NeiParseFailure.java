package com.vantor.nitf.nei;

/** A supported extension that was present but did not match the expected layout. */
public final class NeiParseFailure {
    private final NeiOccurrence.Container container;
    private final int segmentIndex;
    private final String type;
    private final String message;

    NeiParseFailure(final NeiOccurrence.Container container, final int segmentIndex,
            final String type, final String message) {
        this.container = container;
        this.segmentIndex = segmentIndex;
        this.type = type;
        this.message = message;
    }

    public NeiOccurrence.Container getContainer() {
        return container;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }
}

