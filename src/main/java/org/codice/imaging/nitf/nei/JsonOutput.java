package org.codice.imaging.nitf.nei;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Minimal JSON serialization for the command-line tool, with no added runtime dependency. */
final class JsonOutput {
    private JsonOutput() {
    }

    static String write(final File file, final List<NeiOccurrence> records,
            final List<NeiParseFailure> failures) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n  \"file\": {\n")
                .append("    \"path\": ").append(quote(file.getAbsolutePath())).append(",\n")
                .append("    \"sizeBytes\": ").append(file.length()).append("\n")
                .append("  },\n  \"records\": [");
        for (int i = 0; i < records.size(); i++) {
            NeiOccurrence occurrence = records.get(i);
            NeiRecord record = occurrence.getRecord();
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    {\n")
                    .append("      \"container\": ").append(quote(occurrence.getContainer().name()))
                    .append(",\n      \"segmentIndex\": ").append(occurrence.getSegmentIndex())
                    .append(",\n      \"type\": ").append(quote(record.getType()))
                    .append(",\n      \"payloadLength\": ").append(record.getPayloadLength())
                    .append(",\n      \"bytesConsumed\": ").append(record.getBytesConsumed())
                    .append(",\n      \"remainingBytes\": ").append(record.getRemainingBytes())
                    .append(",\n      \"fields\": {");
            int fieldIndex = 0;
            for (Map.Entry<String, String> field : record.getFields().entrySet()) {
                if (fieldIndex++ > 0) {
                    json.append(',');
                }
                json.append("\n        ").append(quote(field.getKey())).append(": ")
                        .append(quote(field.getValue()));
            }
            if (!record.getFields().isEmpty()) {
                json.append('\n');
            }
            json.append("      }\n    }");
        }
        if (!records.isEmpty()) {
            json.append('\n');
        }
        json.append("  ],\n  \"failures\": [");
        for (int i = 0; i < failures.size(); i++) {
            NeiParseFailure failure = failures.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("\n    {\n")
                    .append("      \"container\": ").append(quote(failure.getContainer().name()))
                    .append(",\n      \"segmentIndex\": ").append(failure.getSegmentIndex())
                    .append(",\n      \"type\": ").append(quote(failure.getType()))
                    .append(",\n      \"message\": ").append(quote(failure.getMessage()))
                    .append("\n    }");
        }
        if (!failures.isEmpty()) {
            json.append('\n');
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String quote(final String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('"').toString();
    }
}

