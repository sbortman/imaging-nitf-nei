package org.codice.imaging.nitf.nei;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small bounds-checked reader for the BCS fixed-width fields used by NEI extensions. */
final class FixedWidthReader {
    private final String type;
    private final byte[] payload;
    private final Map<String, String> fields = new LinkedHashMap<>();
    private int position;

    FixedWidthReader(final String type, final byte[] payload) {
        this.type = type;
        this.payload = payload.clone();
    }

    String text(final String name, final int length) {
        if (length < 0 || position + length > payload.length) {
            throw new NeiFormatException(String.format(
                    "%s field %s needs %d bytes at offset %d, payload has %d",
                    type, name, length, position, payload.length));
        }
        String value = new String(payload, position, length, StandardCharsets.ISO_8859_1);
        position += length;
        fields.put(name, value);
        return value;
    }

    int integer(final String name, final int length) {
        String raw = text(name, length).trim();
        if (raw.isEmpty()) {
            throw new NeiFormatException(type + " field " + name + " is blank");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new NeiFormatException(type + " field " + name + " is not an integer: " + raw, e);
        }
    }

    int position() {
        return position;
    }

    int remaining() {
        return payload.length - position;
    }

    char peek(final int relativeOffset) {
        int offset = position + relativeOffset;
        if (offset < 0 || offset >= payload.length) {
            throw new NeiFormatException(type + " peek outside payload at offset " + offset);
        }
        return (char) (payload[offset] & 0xff);
    }

    void finishReserved(final String lengthField, final int length) {
        if (length > 0) {
            text(lengthField + "_DATA", length);
        }
    }

    NeiRecord record() {
        return new NeiRecord(type, fields, payload.length, position);
    }
}
