package com.vantor.nitf.nei;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Presents non-conformant blank NITF 2.1 ENCRYP fields as the required {@code 0}.
 * The source file is never changed. Only offsets derived from the NITF header are considered.
 */
final class Nitf21BlankEncrypInputStream extends FilterInputStream {
    private static final long FILE_ENCRYP_OFFSET = 296;
    private static final long HEADER_LENGTH_OFFSET = 354;
    private static final long SEGMENT_TABLE_OFFSET = 360;
    private static final long IMAGE_ENCRYP_OFFSET = 290;
    private static final long GRAPHIC_ENCRYP_OFFSET = 199;
    private static final long TEXT_ENCRYP_OFFSET = 273;

    private final Set<Long> encrypOffsets;
    private long position;

    private Nitf21BlankEncrypInputStream(final File file, final Set<Long> encrypOffsets)
            throws IOException {
        super(new FileInputStream(file));
        this.encrypOffsets = encrypOffsets;
    }

    static Nitf21BlankEncrypInputStream open(final File file) throws IOException {
        return new Nitf21BlankEncrypInputStream(file, locateEncrypFields(file));
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            return value;
        }
        long offset = position++;
        return value == ' ' && encrypOffsets.contains(offset) ? '0' : value;
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
        int count = super.read(bytes, offset, length);
        if (count <= 0) {
            return count;
        }
        for (int i = 0; i < count; i++) {
            long absolute = position + i;
            if (bytes[offset + i] == ' ' && encrypOffsets.contains(absolute)) {
                bytes[offset + i] = '0';
            }
        }
        position += count;
        return count;
    }

    @Override
    public long skip(final long count) throws IOException {
        long skipped = super.skip(count);
        position += skipped;
        return skipped;
    }

    private static Set<Long> locateEncrypFields(final File file) throws IOException {
        Set<Long> offsets = new HashSet<>();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[9];
            input.readFully(magic);
            if (!"NITF02.10".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return offsets;
            }
            offsets.add(FILE_ENCRYP_OFFSET);
            long headerLength = asciiLong(input, HEADER_LENGTH_OFFSET, 6);
            long table = SEGMENT_TABLE_OFFSET;

            int imageCount = (int) asciiLong(input, table, 3);
            table += 3;
            long[] imageHeaders = new long[imageCount];
            long[] imageData = new long[imageCount];
            for (int i = 0; i < imageCount; i++) {
                imageHeaders[i] = asciiLong(input, table, 6);
                table += 6;
                imageData[i] = asciiLong(input, table, 10);
                table += 10;
            }

            int graphicCount = (int) asciiLong(input, table, 3);
            table += 3;
            long[] graphicHeaders = new long[graphicCount];
            long[] graphicData = new long[graphicCount];
            for (int i = 0; i < graphicCount; i++) {
                graphicHeaders[i] = asciiLong(input, table, 4);
                table += 4;
                graphicData[i] = asciiLong(input, table, 6);
                table += 6;
            }

            // NUMX is reserved in NITF 2.1 and has no following segment table entries.
            asciiLong(input, table, 3);
            table += 3;
            int textCount = (int) asciiLong(input, table, 3);
            table += 3;
            long[] textHeaders = new long[textCount];
            long[] textData = new long[textCount];
            for (int i = 0; i < textCount; i++) {
                textHeaders[i] = asciiLong(input, table, 4);
                table += 4;
                textData[i] = asciiLong(input, table, 5);
                table += 5;
            }

            long segmentStart = headerLength;
            for (int i = 0; i < imageCount; i++) {
                offsets.add(segmentStart + IMAGE_ENCRYP_OFFSET);
                segmentStart += imageHeaders[i] + imageData[i];
            }
            for (int i = 0; i < graphicCount; i++) {
                offsets.add(segmentStart + GRAPHIC_ENCRYP_OFFSET);
                segmentStart += graphicHeaders[i] + graphicData[i];
            }
            for (int i = 0; i < textCount; i++) {
                offsets.add(segmentStart + TEXT_ENCRYP_OFFSET);
                segmentStart += textHeaders[i] + textData[i];
            }
        }
        return offsets;
    }

    private static long asciiLong(final RandomAccessFile input, final long offset, final int length)
            throws IOException {
        byte[] value = new byte[length];
        input.seek(offset);
        input.readFully(value);
        String text = new String(value, StandardCharsets.US_ASCII);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid NITF numeric field at offset " + offset + ": " + text, e);
        }
    }
}

