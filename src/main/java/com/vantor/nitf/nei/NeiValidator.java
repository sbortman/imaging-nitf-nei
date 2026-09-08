package com.vantor.nitf.nei;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.codice.imaging.nitf.core.common.NitfFormatException;
import org.codice.imaging.nitf.core.dataextension.DataExtensionSegment;
import org.codice.imaging.nitf.core.header.NitfHeader;
import org.codice.imaging.nitf.core.image.ImageCoordinatePair;
import org.codice.imaging.nitf.core.image.ImageCoordinates;
import org.codice.imaging.nitf.core.image.ImageCoordinatesRepresentation;
import org.codice.imaging.nitf.core.image.ImageSegment;
import org.codice.imaging.nitf.core.impl.SlottedParseStrategy;
import org.codice.imaging.nitf.core.security.SecurityClassification;
import org.codice.imaging.nitf.core.security.SecurityMetadata;
import org.codice.imaging.nitf.core.tre.Tre;
import org.codice.imaging.nitf.fluent.NitfSegmentsFlow;
import org.codice.imaging.nitf.fluent.impl.NitfParserInputFlowImpl;

/**
 * Reports defects in a NEI NITF using only the file itself.
 *
 * <p>This is deliberately self-contained: it needs no level1a XML, no focused
 * sidecar and no vendor reconstruction, so it can be pointed at any product,
 * including one whose sources are long gone. What it can therefore check is
 * conformance (fields a standard requires) and internal consistency (two places
 * in the same file that must agree). It cannot check whether a populated value
 * is the *right* value -- comparing against sources is a different tool's job.
 *
 * <p>Every rule states what it found and what it expected. A check that cannot
 * state both is emitted as INFO, never as a defect, because a validator that
 * reports a failure it cannot substantiate is worse than one that stays quiet.
 */
public final class NeiValidator {

    /** DES identifiers a NEI product is expected to carry. */
    private static final Set<String> EXPECTED_DES = new LinkedHashSet<>(
            Arrays.asList("CSEPHB", "CSATTB", "CSSFAB", "CSCSDB"));

    /** Values the header conformance bug settled on. */
    private static final String EXPECTED_OSTAID = "Vantor";
    private static final String EXPECTED_CLAS_SYSTEM = "US";

    private final NeiNitfAdapter adapter;

    public NeiValidator() {
        this(new NeiNitfAdapter());
    }

    public NeiValidator(final NeiNitfAdapter adapter) {
        this.adapter = adapter;
    }

    /** Everything one image segment contributes to the rules. */
    private static final class ImageFacts {
        private int index;
        private String identifier = "";
        private String dateTime = "";
        private SecurityMetadata security;
        private long rows;
        private long cols;
        private int numBands;
        private int blocksPerRow;
        private int blocksPerColumn;
        private long pixelsPerBlockH;
        private long pixelsPerBlockV;
        private String compression = "";
        private ImageCoordinatesRepresentation coordsRep;
        private ImageCoordinates coords;
        private final Map<String, byte[]> rawTres = new LinkedHashMap<>();
        private final List<NeiRecord> records = new ArrayList<>();
    }

    public NeiValidationReport validate(final File file) throws NitfFormatException {
        NeiValidationReport report = new NeiValidationReport();
        List<ImageFacts> images = new ArrayList<>();
        List<String> desIds = new ArrayList<>();
        List<NeiRecord> desRecords = new ArrayList<>();
        AtomicInteger imageIndex = new AtomicInteger();
        AtomicInteger desIndex = new AtomicInteger();

        try (InputStream input = Nitf21BlankEncrypInputStream.open(file)) {
            NitfSegmentsFlow flow = new NitfParserInputFlowImpl()
                    .inputStream(input)
                    .build(new SlottedParseStrategy(SlottedParseStrategy.DES_DATA));
            try {
                flow.fileHeader(header -> checkFileHeader(report, header));
                flow.forEachImageSegment(image ->
                        images.add(collectImage(report, image, imageIndex.incrementAndGet())));
                flow.forEachDataExtensionSegment(des -> {
                    int index = desIndex.incrementAndGet();
                    desIds.add(des.getIdentifier().trim());
                    checkDes(report, des, index, desRecords);
                });
            } finally {
                flow.end();
            }
        } catch (IOException e) {
            throw new NeiFormatException("Could not read " + file, e);
        }

        for (ImageFacts facts : images) {
            checkImage(report, facts);
        }
        checkDesInventory(report, desIds);
        crossCheckDesAgainstImages(report, images, desRecords);
        return report;
    }

    // ------------------------------------------------------------------ file

    private void checkFileHeader(final NeiValidationReport report, final NitfHeader header) {
        String where = "FILE";
        String ostaid = trim(header.getOriginatingStationId());
        if (ostaid.isEmpty()) {
            report.add(NeiFinding.error("HDR-OSTAID-BLANK", where, "OSTAID", ostaid,
                    "'" + EXPECTED_OSTAID + "'",
                    "MIL-STD-2500C requires an originating station; consumers use it for provenance"));
        }
        SecurityMetadata security = header.getFileSecurityMetadata();
        if (security != null) {
            checkClassification(report, where, "FSCLAS", "FSCLSY", security,
                    "HDR-FSCLAS-BLANK", "HDR-FSCLSY-BLANK");
        }
        if (trim(header.getFileTitle()).isEmpty()) {
            report.add(NeiFinding.warn("HDR-FTITLE-BLANK", where, "FTITLE", "", "a product title",
                    "legal but leaves the file unidentifiable outside its filename"));
        }
        String fdt = header.getFileDateTime() == null ? ""
                : trim(header.getFileDateTime().getSourceString());
        if (fdt.isEmpty() || isPlaceholder(fdt)) {
            report.add(NeiFinding.error("HDR-FDT-INVALID", where, "FDT", fdt, "CCYYMMDDhhmmss",
                    "file date/time is unset or a placeholder"));
        }
    }

    // ----------------------------------------------------------------- image

    private ImageFacts collectImage(final NeiValidationReport report, final ImageSegment image,
            final int index) {
        ImageFacts facts = new ImageFacts();
        facts.index = index;
        facts.identifier = trim(image.getIdentifier());
        facts.dateTime = image.getImageDateTime() == null ? ""
                : trim(image.getImageDateTime().getSourceString());
        facts.security = image.getSecurityMetadata();
        facts.rows = image.getNumberOfRows();
        facts.cols = image.getNumberOfColumns();
        facts.numBands = image.getNumBands();
        facts.blocksPerRow = image.getNumberOfBlocksPerRow();
        facts.blocksPerColumn = image.getNumberOfBlocksPerColumn();
        facts.pixelsPerBlockH = image.getNumberOfPixelsPerBlockHorizontal();
        facts.pixelsPerBlockV = image.getNumberOfPixelsPerBlockVertical();
        facts.compression = image.getImageCompression() == null ? ""
                : image.getImageCompression().name();
        facts.coordsRep = image.getImageCoordinatesRepresentation();
        facts.coords = image.getImageCoordinates();

        for (Tre tre : image.getTREsRawStructure().getTREs()) {
            byte[] raw = tre.getRawData();
            if (raw != null) {
                facts.rawTres.put(tre.getName().trim(), raw);
            }
            try {
                adapter.parse(tre).ifPresent(facts.records::add);
            } catch (NeiFormatException e) {
                report.add(NeiFinding.error("TRE-PARSE-FAILURE", "IMAGE " + index,
                        tre.getName().trim(), e.getMessage(), "a parseable record",
                        "the TRE payload does not match its registered layout"));
            }
        }
        return facts;
    }

    private void checkImage(final NeiValidationReport report, final ImageFacts facts) {
        String where = "IMAGE " + facts.index;
        if (facts.identifier.isEmpty()) {
            report.add(NeiFinding.warn("IMG-IID1-BLANK", where, "IID1", "", "a product identifier",
                    "nothing inside the file names this image"));
        }
        if (facts.dateTime.isEmpty() || isPlaceholder(facts.dateTime)) {
            report.add(NeiFinding.error("IMG-IDATIM-INVALID", where, "IDATIM", facts.dateTime,
                    "CCYYMMDDhhmmss",
                    "acquisition time is a placeholder, yet the ephemeris DES carry real epochs"));
        }
        if (facts.security != null) {
            checkClassification(report, where, "ISCLAS", "ISCLSY", facts.security,
                    "IMG-ISCLAS-BLANK", "IMG-ISCLSY-BLANK");
        }
        checkCoordinates(report, facts, where);
        checkBlocking(report, facts, where);
        checkBandAgreement(report, facts, where);

        for (NeiRecord record : facts.records) {
            checkRecordLayout(report, where, record);
            if ("CSEXRB".equals(record.getType())) {
                checkCsexrb(report, where, record, facts);
            }
        }
        report.add(NeiFinding.info("IMG-SUMMARY", where, "",
                facts.rows + "x" + facts.cols + " x" + facts.numBands + "band "
                        + facts.compression,
                "blocks " + facts.blocksPerRow + "x" + facts.blocksPerColumn + " of "
                        + facts.pixelsPerBlockH + "x" + facts.pixelsPerBlockV));
    }

    /**
     * ICORDS names a coordinate system; IGEOLO carries the corners. Declaring one
     * without the other is worse than declaring neither: a consumer that trusts
     * ICORDS will read four blank corners as valid coordinates.
     *
     * <p>Blank/blank is deliberate for GLAS-GFM products and is NOT reported.
     */
    private void checkCoordinates(final NeiValidationReport report, final ImageFacts facts,
            final String where) {
        ImageCoordinatesRepresentation rep = facts.coordsRep;
        if (rep == null || rep == ImageCoordinatesRepresentation.NONE
                || rep == ImageCoordinatesRepresentation.UNKNOWN) {
            return;
        }
        if (facts.coords == null) {
            report.add(NeiFinding.error("IMG-ICORDS-WITHOUT-IGEOLO", where, "IGEOLO", "",
                    "four corner coordinates",
                    "ICORDS declares " + rep.name() + " but no IGEOLO was parsed"));
            return;
        }
        boolean allBlank = true;
        for (ImageCoordinatePair corner : corners(facts.coords)) {
            if (corner != null && !trim(corner.getSourceFormat()).isEmpty()) {
                allBlank = false;
                break;
            }
        }
        if (allBlank) {
            report.add(NeiFinding.error("IMG-ICORDS-WITHOUT-IGEOLO", where, "IGEOLO", "(blank)",
                    "four corner coordinates",
                    "ICORDS declares " + rep.name()
                            + " while IGEOLO is blank; leave ICORDS blank instead"));
        }
    }

    private static List<ImageCoordinatePair> corners(final ImageCoordinates coords) {
        return Arrays.asList(coords.getCoordinate00(), coords.getCoordinate0MaxCol(),
                coords.getCoordinateMaxRowMaxCol(), coords.getCoordinateMaxRow0());
    }

    /** The block grid must cover the image, or the tail of it is unreachable. */
    private void checkBlocking(final NeiValidationReport report, final ImageFacts facts,
            final String where) {
        long coveredCols = (long) facts.blocksPerRow * facts.pixelsPerBlockH;
        long coveredRows = (long) facts.blocksPerColumn * facts.pixelsPerBlockV;
        if (coveredCols > 0 && coveredCols < facts.cols) {
            report.add(NeiFinding.error("IMG-BLOCKS-SHORT-COLS", where, "NBPR*NPPBH",
                    Long.toString(coveredCols), ">= NCOLS " + facts.cols,
                    "the block grid does not span the declared width"));
        }
        if (coveredRows > 0 && coveredRows < facts.rows) {
            report.add(NeiFinding.error("IMG-BLOCKS-SHORT-ROWS", where, "NBPC*NPPBV",
                    Long.toString(coveredRows), ">= NROWS " + facts.rows,
                    "the block grid does not span the declared height"));
        }
    }

    /**
     * The band count is stated in three independent places. They must agree, and
     * when they disagree the image subheader is authoritative -- it is what a
     * reader uses to find pixels.
     */
    private void checkBandAgreement(final NeiValidationReport report, final ImageFacts facts,
            final String where) {
        Integer bandsb = leadingInt(facts.rawTres.get("BANDSB"), 5);
        if (bandsb != null && bandsb != facts.numBands) {
            report.add(NeiFinding.error("IMG-BANDS-VS-BANDSB", where, "BANDSB.COUNT",
                    Integer.toString(bandsb), "NBANDS " + facts.numBands,
                    "BANDSB describes a different number of bands than the image carries"));
        }
    }

    /**
     * CSSFAB is carried as a DES, not an image TRE, so its band count can only be
     * compared once both passes are done.
     *
     * <p>With more than one image segment the DES-to-image association would have
     * to come from CSEXRB's ASSOC_DES UUIDs; until that is wired up this reports
     * the ambiguity rather than guessing which image a DES describes.
     */
    private void crossCheckDesAgainstImages(final NeiValidationReport report,
            final List<ImageFacts> images, final List<NeiRecord> desRecords) {
        for (NeiRecord record : desRecords) {
            if (!"CSSFAB".equals(record.getType())) {
                continue;
            }
            if (record.getFields().containsKey("VENDOR_PREAMBLE")) {
                report.add(NeiFinding.warn("CSSFAB-VENDOR-PREAMBLE", "FILE", "CSSFAB", "present",
                        "a registered CSSFAB body",
                        "a legacy preamble precedes the registered layout"));
            }
            Integer cssfab = parseInt(record.get("N_BANDS"));
            if (cssfab == null) {
                continue;
            }
            if (images.size() != 1) {
                report.add(NeiFinding.info("CSSFAB-BANDS-UNCHECKED", "FILE", "CSSFAB.N_BANDS",
                        Integer.toString(cssfab),
                        images.size() + " image segments: cannot tell which one this DES describes"));
                continue;
            }
            ImageFacts facts = images.get(0);
            if (cssfab != facts.numBands) {
                report.add(NeiFinding.error("BANDS-VS-CSSFAB", "IMAGE " + facts.index,
                        "CSSFAB.N_BANDS", Integer.toString(cssfab),
                        "NBANDS " + facts.numBands,
                        "the focal-plane record describes a different band count"));
            }
        }
    }

    /** CSEXRB restates the image size and identifies the product. Both must hold. */
    private void checkCsexrb(final NeiValidationReport report, final String where,
            final NeiRecord record, final ImageFacts facts) {
        compareCount(report, where, "CSEXRB.NUM_LINES", record.get("NUM_LINES"), facts.rows,
                "CSEXRB-NUM-LINES", "NROWS");
        compareCount(report, where, "CSEXRB.NUM_SAMPLES", record.get("NUM_SAMPLES"), facts.cols,
                "CSEXRB-NUM-SAMPLES", "NCOLS");

        String uuid = trim(record.get("IMAGE_UUID"));
        if (uuid.isEmpty()) {
            report.add(NeiFinding.error("CSEXRB-IMAGE-UUID-BLANK", where, "CSEXRB.IMAGE_UUID", "",
                    "a 36-character UUID", "nothing ties this image to its DES records"));
        } else if (uuid.length() != 36) {
            report.add(NeiFinding.error("CSEXRB-IMAGE-UUID-MALFORMED", where,
                    "CSEXRB.IMAGE_UUID", uuid, "a 36-character UUID",
                    "length is " + uuid.length()));
        }
        if (trim(record.get("SENSOR_ID")).isEmpty()) {
            report.add(NeiFinding.warn("CSEXRB-SENSOR-ID-BLANK", where, "CSEXRB.SENSOR_ID", "",
                    "PAN / MS / a sensor name", "consumers key processing off the sensor"));
        }
        Integer assoc = parseInt(record.get("NUM_ASSOC_DES"));
        if (assoc == null) {
            return;
        }
        for (int i = 0; i < assoc; i++) {
            String key = "ASSOC_DES[" + i + "].ASSOC_DES_UUID";
            String value = record.getFields().get(key);
            if (value == null) {
                continue;
            }
            if (trim(value).isEmpty()) {
                report.add(NeiFinding.error("CSEXRB-ASSOC-UUID-BLANK", where, key, "",
                        "a 36-character UUID",
                        "NUM_ASSOC_DES claims " + assoc + " associations; this one is empty"));
            }
        }
    }

    private void compareCount(final NeiValidationReport report, final String where,
            final String field, final String raw, final long expected, final String code,
            final String expectedName) {
        String value = trim(raw);
        if (value.isEmpty()) {
            report.add(NeiFinding.error(code + "-BLANK", where, field, "",
                    expectedName + " " + expected, "the field is unpopulated"));
            return;
        }
        Integer actual = parseInt(value);
        if (actual == null) {
            report.add(NeiFinding.error(code + "-MALFORMED", where, field, value,
                    "an integer equal to " + expectedName + " " + expected, "not a number"));
            return;
        }
        if (actual != expected) {
            report.add(NeiFinding.error(code, where, field, Integer.toString(actual),
                    expectedName + " " + expected, "the TRE and the image disagree"));
        }
    }

    /** A record that stops short of its payload has an unread tail: a layout error. */
    private void checkRecordLayout(final NeiValidationReport report, final String where,
            final NeiRecord record) {
        int remaining = record.getRemainingBytes();
        if (remaining != 0) {
            report.add(NeiFinding.error("TRE-TRAILING-BYTES", where, record.getType(),
                    record.getBytesConsumed() + " of " + record.getPayloadLength() + " bytes",
                    "the whole payload consumed",
                    remaining + " byte(s) unaccounted for"));
        }
    }

    // ------------------------------------------------------------------- des

    private void checkDes(final NeiValidationReport report, final DataExtensionSegment des,
            final int index, final List<NeiRecord> collected) {
        String id = des.getIdentifier().trim();
        String where = "DES " + index + " (" + id + ")";
        SecurityMetadata security = des.getSecurityMetadata();
        if (security != null && isBlankClassification(security)) {
            report.add(NeiFinding.error("DES-DESCLAS-BLANK", where, "DESCLAS", "", "'U'",
                    "the DES subheader carries no classification"));
        }
        try {
            adapter.parse(des).ifPresent(record -> {
                collected.add(record);
                checkRecordLayout(report, where, record);
                if ("CSEPHB".equals(record.getType())) {
                    describeEphemeris(report, where, record);
                }
            });
        } catch (NeiFormatException e) {
            report.add(NeiFinding.error("DES-PARSE-FAILURE", where, id, e.getMessage(),
                    "a parseable record", "the DES payload does not match its layout"));
        }
    }

    /**
     * Reports the ephemeris epoch and span as context, not as a defect. The
     * multi-segment ephemeris bug shows up here as a span that cannot cover the
     * image, but proving that needs the image's own acquisition window -- and
     * IDATIM is a placeholder on exactly the products that have the bug.
     */
    private void describeEphemeris(final NeiValidationReport report, final String where,
            final NeiRecord record) {
        String date = trim(record.get("DATE_EPHEM"));
        String t0 = trim(record.get("T0_EPHEM"));
        Integer count = parseInt(record.get("NUM_EPHEM"));
        Double dt = parseDouble(record.get("DT_EPHEM"));
        if (count == null || dt == null) {
            return;
        }
        double span = dt * Math.max(0, count - 1);
        report.add(NeiFinding.info("CSEPHB-SPAN", where, "",
                count + " samples at " + dt + "s",
                "epoch " + date + " " + t0 + ", span " + String.format("%.3f", span) + "s"));
        if (count <= 1) {
            report.add(NeiFinding.warn("CSEPHB-SINGLE-SAMPLE", where, "NUM_EPHEM",
                    Integer.toString(count), ">= 2",
                    "a single ephemeris sample cannot describe motion"));
        }
    }

    private void checkDesInventory(final NeiValidationReport report, final List<String> ids) {
        Set<String> present = new HashSet<>(ids);
        for (String expected : EXPECTED_DES) {
            if (!present.contains(expected)) {
                report.add(NeiFinding.warn("DES-MISSING", "FILE", expected, "absent", "present",
                        "a NEI product is expected to carry this DES"));
            }
        }
        report.add(NeiFinding.info("DES-INVENTORY", "FILE", "", String.join(", ", ids),
                ids.size() + " data extension segment(s)"));
    }

    // ---------------------------------------------------------------- shared

    private void checkClassification(final NeiValidationReport report, final String where,
            final String clasField, final String systemField, final SecurityMetadata security,
            final String clasCode, final String systemCode) {
        if (isBlankClassification(security)) {
            report.add(NeiFinding.error(clasCode, where, clasField, "", "'U'",
                    "classification is required on every segment"));
        }
        if (trim(security.getSecurityClassificationSystem()).isEmpty()) {
            report.add(NeiFinding.error(systemCode, where, systemField, "",
                    "'" + EXPECTED_CLAS_SYSTEM + "'",
                    "the classification system must name the owning authority"));
        }
    }

    private static boolean isBlankClassification(final SecurityMetadata security) {
        SecurityClassification classification = security.getSecurityClassification();
        return classification == null || classification == SecurityClassification.UNKNOWN;
    }

    /** True for a field filled with hyphens or spaces, which some writers use for "unset". */
    static boolean isPlaceholder(final String value) {
        if (value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '-' && c != ' ' && c != '0') {
                return false;
            }
        }
        return true;
    }

    /** Reads a fixed-width leading integer out of a raw TRE payload. */
    static Integer leadingInt(final byte[] raw, final int width) {
        if (raw == null || raw.length < width) {
            return null;
        }
        StringBuilder text = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            text.append((char) (raw[i] & 0xFF));
        }
        return parseInt(text.toString());
    }

    static Integer parseInt(final String value) {
        String text = trim(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(final String value) {
        String text = trim(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String trim(final String value) {
        return value == null ? "" : value.trim();
    }
}
