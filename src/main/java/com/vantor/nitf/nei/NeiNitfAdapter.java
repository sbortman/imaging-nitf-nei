package com.vantor.nitf.nei;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.stream.ImageInputStream;
import org.codice.imaging.nitf.core.common.NitfFormatException;
import org.codice.imaging.nitf.core.dataextension.DataExtensionSegment;
import org.codice.imaging.nitf.core.impl.SlottedParseStrategy;
import org.codice.imaging.nitf.core.tre.Tre;
import org.codice.imaging.nitf.fluent.NitfSegmentsFlow;
import org.codice.imaging.nitf.fluent.impl.NitfParserInputFlowImpl;

/** Bridges stock imaging-nitf 0.10 segment objects to the standalone NEI parser. */
public final class NeiNitfAdapter {
    private final NeiExtensionParser parser;

    public NeiNitfAdapter() {
        this(new NeiExtensionParser());
    }

    public NeiNitfAdapter(final NeiExtensionParser parser) {
        this.parser = parser;
    }

    /** Parses an unknown TRE retained as raw bytes by stock imaging-nitf. */
    public Optional<NeiRecord> parse(final Tre tre) {
        if (tre == null || !parser.supports(tre.getName())) {
            return Optional.empty();
        }
        byte[] raw = tre.getRawData();
        if (raw == null) {
            throw new NeiFormatException("TRE " + tre.getName()
                    + " has already been decoded and no raw payload is available");
        }
        return Optional.of(parser.parse(tre.getName(), raw));
    }

    /** Reads and parses a supported GLAS/GFM DES payload. */
    public Optional<NeiRecord> parse(final DataExtensionSegment des) {
        if (des == null || !parser.supports(des.getIdentifier())) {
            return Optional.empty();
        }
        long length = des.getDataLength();
        if (length > Integer.MAX_VALUE) {
            throw new NeiFormatException("DES is too large to buffer: " + length);
        }
        byte[] payload = new byte[(int) length];
        boolean[] consumed = {false};
        des.consume(stream -> {
            readFully(stream, payload);
            consumed[0] = true;
        });
        if (!consumed[0]) {
            throw new NeiFormatException("DES payload was not extracted; parse with DES_DATA enabled");
        }
        return Optional.of(parser.parse(des.getIdentifier(), des.getDESVersion(), payload));
    }

    /**
     * Scans a file using only released imaging-nitf 0.10 artifacts.
     * Image pixels are skipped; DES payloads are retained long enough to decode them.
     */
    public List<NeiOccurrence> scan(final File file)
            throws FileNotFoundException, NitfFormatException {
        return scanReport(file).getRecords();
    }

    /** Scans while retaining per-extension parse failures instead of aborting the whole file. */
    public NeiScanReport scanReport(final File file)
            throws FileNotFoundException, NitfFormatException {
        NeiScanReport report = new NeiScanReport();
        AtomicInteger imageIndex = new AtomicInteger();
        AtomicInteger desIndex = new AtomicInteger();

        try (InputStream input = Nitf21BlankEncrypInputStream.open(file)) {
            NitfSegmentsFlow flow = new NitfParserInputFlowImpl()
                    .inputStream(input)
                    .build(new SlottedParseStrategy(SlottedParseStrategy.DES_DATA));
            try {
            flow.forEachImageSegment(image -> {
                int index = imageIndex.incrementAndGet();
                for (Tre tre : image.getTREsRawStructure().getTREs()) {
                    try {
                        parse(tre).ifPresent(record -> report.add(new NeiOccurrence(
                                NeiOccurrence.Container.IMAGE_TRE, index, record)));
                    } catch (NeiFormatException e) {
                        report.fail(NeiOccurrence.Container.IMAGE_TRE, index, tre.getName(), e);
                    }
                }
            });
            flow.forEachDataExtensionSegment(des -> {
                int index = desIndex.incrementAndGet();
                try {
                    parse(des).ifPresent(record -> report.add(new NeiOccurrence(
                            NeiOccurrence.Container.DATA_EXTENSION, index, record)));
                } catch (NeiFormatException e) {
                    report.fail(NeiOccurrence.Container.DATA_EXTENSION, index,
                            des.getIdentifier().trim(), e);
                }
            });
            } finally {
                flow.end();
            }
        } catch (IOException e) {
            throw new NeiFormatException("Could not read " + file, e);
        }
        return report;
    }

    private static void readFully(final ImageInputStream stream, final byte[] payload) {
        try {
            stream.seek(0);
            stream.readFully(payload);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read DES payload", e);
        }
    }
}
