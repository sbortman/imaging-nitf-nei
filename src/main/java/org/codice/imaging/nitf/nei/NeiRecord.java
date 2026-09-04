package org.codice.imaging.nitf.nei;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A parsed NEI extension represented independently of imaging-nitf internals. */
public final class NeiRecord {
    private final String type;
    private final Map<String, String> fields;
    private final int payloadLength;
    private final int bytesConsumed;

    NeiRecord(final String type, final Map<String, String> fields,
            final int payloadLength, final int bytesConsumed) {
        this.type = Objects.requireNonNull(type, "type");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.payloadLength = payloadLength;
        this.bytesConsumed = bytesConsumed;
    }

    public String getType() {
        return type;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public String get(final String name) {
        return fields.get(name);
    }

    public int getInt(final String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No field named " + name);
        }
        return Integer.parseInt(value.trim());
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public int getBytesConsumed() {
        return bytesConsumed;
    }

    public int getRemainingBytes() {
        return payloadLength - bytesConsumed;
    }

    @Override
    public String toString() {
        return type + fields;
    }
}

