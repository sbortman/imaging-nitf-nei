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
        private String irep = "";
        private final List<String> bandReps = new ArrayList<>();
        private final Map<String, byte[]> rawTres = new LinkedHashMap<>();
        private final List<NeiRecord> records = new ArrayList<>();
    }

    public NeiValidationReport validate(final File file) throws NitfFormatException {
        NeiValidationReport report = new NeiValidationReport();
        List<ImageFacts> images = new ArrayList<>();
        List<String> desIds = new ArrayList<>();
        Map<NeiRecord, String> desRecords = new LinkedHashMap<>();
        Map<NeiRecord, Integer> desVersions = new LinkedHashMap<>();
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
                    checkDes(report, des, index, desRecords, desVersions);
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
        checkReferenceFrameAgreement(report, desRecords, desVersions);
        return report;
    }

    // ------------------------------------------------------------------ file

    private void checkFileHeader(final NeiValidationReport report, final NitfHeader header) {
        String where = "FILE";
        String ostaid = trim(header.getOriginatingStationId());
        if (ostaid.isEmpty()) {
            report.add(NeiFinding.error("HDR-OSTAID-BLANK", where, "OSTAID", ostaid,
                    "a station identifier, e.g. '" + EXPECTED_OSTAID + "'",
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
        facts.irep = image.getImageRepresentation() == null ? ""
                : trim(image.getImageRepresentation().getTextEquivalent());
        for (int b = 0; b < facts.numBands; b++) {
            facts.bandReps.add(image.getImageBandZeroBase(b) == null ? ""
                    : image.getImageBandZeroBase(b).getImageRepresentation());
        }

        for (Tre tre : image.getTREsRawStructure().getTREs()) {
            byte[] raw = tre.getRawData();
            if (raw == null && "ICHIPB".equals(tre.getName().trim())) {
                raw = ichipbFromEntries(tre);
            }
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
                    "legal under MIL-STD-2500C, but an NEI product is expected to state its "
                            + "acquisition time here"));
        }
        if (facts.security != null) {
            checkClassification(report, where, "ISCLAS", "ISCLSY", facts.security,
                    "IMG-ISCLAS-BLANK", "IMG-ISCLSY-BLANK");
        }
        checkCoordinates(report, facts, where);
        checkBlocking(report, facts, where);
        checkBandAgreement(report, facts, where);
        bandRepresentationFindings(where, facts.irep, facts.bandReps).forEach(report::add);
        ichipbGridFindings(where, facts.rawTres.get("ICHIPB")).forEach(report::add);

        for (NeiRecord record : facts.records) {
            checkRecordLayout(report, where, record);
            if ("CSEXRB".equals(record.getType())) {
                checkCsexrb(report, where, record, facts);
            }
        }
        report.add(NeiFinding.info("IMG-SUMMARY", where, "",
                "NROWS " + facts.rows + " x NCOLS " + facts.cols + ", " + facts.numBands
                        + " band(s), " + facts.compression,
                "NBPC " + facts.blocksPerColumn + " x NBPR " + facts.blocksPerRow
                        + " blocks of NPPBV " + facts.pixelsPerBlockV + " x NPPBH "
                        + facts.pixelsPerBlockH));
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
            final List<ImageFacts> images, final Map<NeiRecord, String> desRecords) {
        for (Map.Entry<NeiRecord, String> entry : desRecords.entrySet()) {
            NeiRecord record = entry.getKey();
            String desWhere = entry.getValue();
            if (!"CSSFAB".equals(record.getType())) {
                continue;
            }
            if (record.getFields().containsKey("VENDOR_PREAMBLE")) {
                report.add(NeiFinding.warn("CSSFAB-VENDOR-PREAMBLE", desWhere, "CSSFAB", "present",
                        "a registered CSSFAB body",
                        "a legacy preamble precedes the registered layout"));
            }
            if (images.size() == 1) {
                // A chip's CSSFAB describes the PARENT's columns; ICHIPB's FI_COL
                // says how many that is. Only an unchipped image is its own width.
                ImageFacts only = images.get(0);
                Long parentCols = ichipbParentCols(only.rawTres.get("ICHIPB"));
                cssfabPairFindings(desWhere, record.getFields(),
                        parentCols != null ? parentCols : only.cols,
                        parentCols != null ? "ICHIPB.FI_COL" : "NCOLS").forEach(report::add);
            }
            Integer cssfab = parseInt(record.get("N_BANDS"));
            if (cssfab == null) {
                continue;
            }
            if (images.size() != 1) {
                report.add(NeiFinding.info("CSSFAB-BANDS-UNCHECKED", desWhere, "CSSFAB.N_BANDS",
                        Integer.toString(cssfab),
                        images.size() + " image segments: cannot tell which one this DES describes"));
                continue;
            }
            ImageFacts facts = images.get(0);
            if (cssfab == 0) {
                // 00000 is not "no bands", it is the sentinel for ALL of them.
                // STDI-0002 Vol 2 App M (GLAS/GFM), N_BANDS: valid values are
                // "00000 (all bands of AIS), or 00001 to 99999", and "If this
                // DES is associated with all the bands in the associated image
                // segment(s), N_BANDS = 0, and fields BAND_INDEXi, IREPBANDi,
                // and ISUBCATi shall be omitted."
                //
                // So a single CSSFAB describing the whole image is CONFORMANT
                // at 0, and the per-band loop being absent is required, not
                // missing. This rule used to flag that as an error -- it was
                // the last "defect" standing on every nei-test-suite product,
                // and acting on it would have meant emitting a loop the spec
                // says shall be omitted.
                report.add(NeiFinding.info("CSSFAB-BANDS-ALL", desWhere, "CSSFAB.N_BANDS",
                        "00000", "all bands of the associated image segment"));
                continue;
            }
            if (cssfab != facts.numBands) {
                report.add(NeiFinding.error("IMG-BANDS-VS-CSSFAB", "IMAGE " + facts.index,
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
            final int index, final Map<NeiRecord, String> collected,
            final Map<NeiRecord, Integer> versions) {
        String id = des.getIdentifier().trim();
        String where = "DES " + index + " (" + id + ")";
        SecurityMetadata security = des.getSecurityMetadata();
        if (security != null && isBlankClassification(security)) {
            report.add(NeiFinding.error("DES-DESCLAS-BLANK", where, "DESCLAS", "", "'U'",
                    "the DES subheader carries no classification"));
        }
        try {
            adapter.parse(des).ifPresent(record -> {
                collected.put(record, where);
                versions.put(record, des.getDESVersion());
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

    /**
     * Attitude and ephemeris must name the same reference frame, and an ECI
     * frame must be one a consumer can actually use.
     *
     * <p>This is the only kind of defect a per-field validator structurally
     * cannot see: {@code 0} is a legal value for {@code ECI_ECF_ATT} and
     * {@code 1} is a legal value for {@code ECI_ECF_EPHEM}, so each field
     * passes in isolation while the file as a whole says the satellite's
     * attitude and its position are measured in different frames. That shipped
     * in every product for six months and every check stayed green.
     *
     * <p>STDI-0002 Vol 2 App M, on both DESes in identical words: in version 1,
     * if ECI is designated "no ECI-to-ECF transformation information is
     * provided [...] and MSP cannot mensurate the dataset". So ECI at
     * {@code DESVER 01} is not merely unusual, it disables the mensuration the
     * rest of the metadata exists to support.
     */
    // Package-private so NeiValidatorSpec can drive it without a file.
    void checkReferenceFrameAgreement(final NeiValidationReport report,
            final Map<NeiRecord, String> desRecords,
            final Map<NeiRecord, Integer> desVersions) {
        Map<String, String> framesByWhere = new LinkedHashMap<>();

        for (Map.Entry<NeiRecord, String> entry : desRecords.entrySet()) {
            NeiRecord record = entry.getKey();
            String where = entry.getValue();
            String field = frameFieldFor(record.getType());
            if (field == null) {
                continue;
            }
            String frame = trim(record.get(field));
            if (frame.isEmpty()) {
                continue;
            }
            framesByWhere.put(where + " " + field, frame);

            Integer version = desVersions.get(record);
            if ("0".equals(frame) && version != null && version < 2) {
                report.add(NeiFinding.error("NEI-FRAME-ECI-UNUSABLE", where, field,
                        "0 (ECI) at DESVER " + String.format("%02d", version),
                        "1 (ECF), or ECI at DESVER 02 with the 32 transform parameters",
                        "STDI-0002 Vol 2 App M: in version 1 no ECI-to-ECF parameters are "
                                + "present, so a consumer cannot mensurate the dataset"));
            }
        }

        Set<String> distinct = new LinkedHashSet<>(framesByWhere.values());
        if (distinct.size() > 1) {
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, String> e : framesByWhere.entrySet()) {
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append(e.getKey()).append('=').append(describeFrame(e.getValue()));
            }
            report.add(NeiFinding.error("NEI-FRAME-DISAGREE", "FILE", "ECI_ECF_*",
                    detail.toString(), "every segment naming the same frame",
                    "attitude and ephemeris describe the same pass and cannot be in "
                            + "different reference frames"));
        } else if (distinct.size() == 1) {
            String frame = distinct.iterator().next();
            report.add(NeiFinding.info("NEI-FRAME", "FILE", "ECI_ECF_*",
                    describeFrame(frame),
                    framesByWhere.size() + " segment(s) agree"));
        }
    }

    /** The frame flag each GLAS-GFM DES spells differently. */
    private static String frameFieldFor(final String type) {
        if ("CSATTB".equals(type)) {
            return "ECI_ECF_ATT";
        }
        if ("CSEPHB".equals(type)) {
            return "ECI_ECF_EPHEM";
        }
        return null;
    }

    private static String describeFrame(final String value) {
        if ("0".equals(value)) {
            return "0 (ECI)";
        }
        if ("1".equals(value)) {
            return "1 (ECF)";
        }
        return value;
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
            // MIL-STD-2500C lets an all-spaces system mean "no system applies", so a
            // blank is legal on an unclassified segment and a defect only on a
            // classified one, where the marking is meaningless without its authority.
            if (isClassified(security)) {
                report.add(NeiFinding.error(systemCode, where, systemField, "",
                        "'" + EXPECTED_CLAS_SYSTEM + "'",
                        "a classified segment must name the owning authority"));
            } else {
                report.add(NeiFinding.warn(systemCode, where, systemField, "",
                        "'" + EXPECTED_CLAS_SYSTEM + "'",
                        "legal when unclassified, but NEI products are expected to name it"));
            }
        }
    }

    private static boolean isClassified(final SecurityMetadata security) {
        SecurityClassification classification = security.getSecurityClassification();
        return classification != null
                && classification != SecurityClassification.UNKNOWN
                && classification != SecurityClassification.UNCLASSIFIED;
    }

    private static boolean isBlankClassification(final SecurityMetadata security) {
        SecurityClassification classification = security.getSecurityClassification();
        return classification == null || classification == SecurityClassification.UNKNOWN;
    }

    /**
     * The IREPBANDn values a NITF 2.1 image may carry, restated in STDI-0002 Vol 1
     * App AR (BCHIPA, IREPBAND_ORIGn): LU, R, G, B, M, Y, Cb, Cr or all spaces;
     * LX and LY are the location-grid values from App P (GEOSDE). The field is
     * case-sensitive -- Cb and Cr are mixed case.
     */
    private static final Set<String> STANDARD_IREPBAND = new HashSet<>(Arrays.asList(
            "", "LU", "R", "G", "B", "M", "Y", "Cb", "Cr", "LX", "LY"));

    /**
     * Values outside the standard set that a producer profile defines. "N" (near-IR)
     * is used by at least one commercial producer's NITF profile for the NIR band of
     * a 4-band product, where the base set would leave it blank. Reported as INFO so
     * a profile-conformant file is not failed and a reader can still see the
     * deviation.
     */
    private static final Set<String> PROFILE_IREPBAND = new HashSet<>(Arrays.asList("N"));

    /** "standard", "profile" or "invalid" for one IREPBANDn value. */
    static String irepbandClass(final String value) {
        String v = value == null ? "" : value.trim();
        if (STANDARD_IREPBAND.contains(v)) {
            return "standard";
        }
        return PROFILE_IREPBAND.contains(v) ? "profile" : "invalid";
    }

    /**
     * IREPBANDn is what a reader uses to find the display bands: a viewer asked for
     * three-band output looks for R, G and B here and, finding none, falls back to
     * bands 1,2,3 -- which on a multispectral image ordered by wavelength puts blue
     * in the red channel. A band INDEX in this field ("00", "01", ...) is invalid
     * under every IREP and leaves the reader nothing to find.
     *
     * <p>IREP=MONO with a band other than "M" is a WARN rather than an ERROR: it is
     * the convention every producer we have seen follows, but the requirement has
     * not been confirmed against MIL-STD-2500C itself.
     */
    static List<NeiFinding> bandRepresentationFindings(final String where, final String irep,
            final List<String> bandReps) {
        List<NeiFinding> out = new ArrayList<>();
        boolean anyRgb = false;
        for (int i = 0; i < bandReps.size(); i++) {
            String value = trim(bandReps.get(i));
            String field = String.format("IREPBAND%03d", i + 1);
            String klass = irepbandClass(value);
            if ("invalid".equals(klass)) {
                out.add(NeiFinding.error("IMG-IREPBAND-INVALID", where, field, value,
                        "one of LU, R, G, B, M, Y, Cb, Cr, LX, LY or spaces",
                        "not a band representation; a reader looking for R, G, B here "
                                + "finds nothing and falls back to bands 1,2,3"));
            } else if ("profile".equals(klass)) {
                out.add(NeiFinding.info("IMG-IREPBAND-PROFILE", where, field, value,
                        "a producer-profile value outside the standard set"));
            } else if ("MONO".equals(irep) && !"M".equals(value)) {
                out.add(NeiFinding.warn("IMG-IREPBAND-MONO", where, field, value, "M",
                        "IREP is MONO; its band is conventionally represented as M"));
            }
            anyRgb |= "R".equals(value) || "G".equals(value) || "B".equals(value);
        }
        if ("MULTI".equals(irep) && bandReps.size() >= 3 && !anyRgb) {
            out.add(NeiFinding.warn("IMG-MULTI-NO-RGB", where, "IREPBANDn",
                    "no R, G or B in " + bandReps.size() + " bands", "R, G and B on the display bands",
                    "legal, but three-band display will fall back to bands 1,2,3"));
        }
        return out;
    }

    /** ICHIPB is fixed-width: 16 bytes of flags, 16 grid values of 12, FI_ROW and FI_COL of 8. */
    private static final int ICHIPB_LENGTH = 224;
    private static final double GRID_EPS = 1e-6;

    /**
     * ICHIPB's grid points sit at the CENTRES of the chip's corner pixels.
     * STDI-0002 Vol 1 App B: the output product's first pixel is centred at
     * (0.5, 0.5), and FI gives the same points in the full image, so FI - OP is
     * the chip's offset. A writer that puts integer corner indices there (0, 0 and
     * N-1) is half a pixel off for any reader that follows the standard -- WARN,
     * because a reader that shares the writer's convention still lands right.
     * OP and FI in DIFFERENT conventions cannot be right for anyone: at unit scale
     * their difference must be a whole number of pixels -- ERROR.
     */
    static List<NeiFinding> ichipbGridFindings(final String where, final byte[] raw) {
        List<NeiFinding> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw.length < ICHIPB_LENGTH) {
            out.add(NeiFinding.error("ICHIPB-SHORT", where, "ICHIPB", raw.length + " bytes",
                    ICHIPB_LENGTH + " bytes", "the fixed-width record is truncated"));
            return out;
        }
        String text = ascii(raw, 0, ICHIPB_LENGTH);
        double[] grid = new double[16];
        for (int i = 0; i < 16; i++) {
            Double v = parseDouble(text.substring(16 + 12 * i, 28 + 12 * i));
            if (v == null) {
                out.add(NeiFinding.error("ICHIPB-MALFORMED", where, "ICHIPB",
                        text.substring(16 + 12 * i, 28 + 12 * i).trim(),
                        "a number in grid field " + (i + 1), "the grid cannot be read"));
                return out;
            }
            grid[i] = v;
        }
        double opRow = grid[0];
        double opCol = grid[1];
        String op11 = trimNumber(opRow) + "," + trimNumber(opCol);
        if (near(opRow, 0.5) && near(opCol, 0.5)) {
            // conformant
        } else if (near(opRow, 0.0) && near(opCol, 0.0)) {
            out.add(NeiFinding.warn("ICHIPB-GRID-CORNERS", where, "ICHIPB.OP_ROW_11,OP_COL_11",
                    op11, "0.5,0.5 (the centre of the first pixel)",
                    "integer corner indices; STDI-0002 Vol 1 App B puts each grid point at a "
                            + "corner pixel's centre, so a conforming reader is half a pixel off"));
        } else {
            out.add(NeiFinding.warn("ICHIPB-GRID-ORIGIN", where, "ICHIPB.OP_ROW_11,OP_COL_11",
                    op11, "0.5,0.5 (the centre of the first pixel)",
                    "the chip's first grid point is not its first pixel"));
        }
        Double scale = parseDouble(text.substring(2, 12));
        boolean unitScale = "00".equals(text.substring(0, 2)) && scale != null && near(scale, 1.0);
        if (unitScale && (!near(frac(grid[8] - opRow), 0.0) || !near(frac(grid[9] - opCol), 0.0))) {
            out.add(NeiFinding.error("ICHIPB-GRID-MIXED", where, "ICHIPB.FI_ROW_11,FI_COL_11",
                    trimNumber(grid[8]) + "," + trimNumber(grid[9]),
                    "the same fractional part as OP_11 (" + op11 + ")",
                    "OP and FI use different pixel conventions; at SCALE_FACTOR 1 FI - OP is "
                            + "the chip offset and must be whole pixels"));
        }
        return out;
    }

    private static final String[] ICHIPB_FIELDS = {"XFRM_FLAG", "SCALE_FACTOR", "ANAMRPH_CORR",
        "SCANBLK_NUM", "OP_ROW_11", "OP_COL_11", "OP_ROW_12", "OP_COL_12", "OP_ROW_21",
        "OP_COL_21", "OP_ROW_22", "OP_COL_22", "FI_ROW_11", "FI_COL_11", "FI_ROW_12",
        "FI_COL_12", "FI_ROW_21", "FI_COL_21", "FI_ROW_22", "FI_COL_22", "FI_ROW", "FI_COL"};
    private static final int[] ICHIPB_WIDTHS = {2, 10, 2, 2, 12, 12, 12, 12, 12, 12, 12, 12,
        12, 12, 12, 12, 12, 12, 12, 12, 8, 8};

    /**
     * imaging-nitf parses ICHIPB itself and keeps no raw payload, so rebuild the
     * fixed-width record from its named entries; the rule then reads one layout
     * whichever way the TRE arrived. Null if any field is missing.
     */
    private static byte[] ichipbFromEntries(final Tre tre) {
        StringBuilder text = new StringBuilder(ICHIPB_LENGTH);
        try {
            for (int i = 0; i < ICHIPB_FIELDS.length; i++) {
                String v = tre.getFieldValue(ICHIPB_FIELDS[i]);
                if (v == null) {
                    return null;
                }
                text.append(String.format("%-" + ICHIPB_WIDTHS[i] + "." + ICHIPB_WIDTHS[i] + "s", v));
            }
        } catch (NitfFormatException e) {
            return null;
        }
        return text.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** ICHIPB.FI_COL, the full image's column count, or null when there is no usable ICHIPB. */
    static Long ichipbParentCols(final byte[] raw) {
        if (raw == null || raw.length < ICHIPB_LENGTH) {
            return null;
        }
        Integer cols = parseInt(ascii(raw, 216, 8));
        return cols == null || cols <= 0 ? null : Long.valueOf(cols);
    }

    /**
     * Two structural properties of a GLAS (SENSOR_TYPE S) field-alignment grid,
     * checkable from the file alone.
     *
     * <p>Pair n starts at sample SMPL_NUM_FIRST + (n-1) * DELTA_SMPL_PAIRS, so the
     * grid spans NUM_FA_PAIRS * DELTA_SMPL_PAIRS columns of the image it describes
     * (the parent, for a chip). A last pair that starts past the image describes
     * nothing -- ERROR. A grid that merely runs past the image, or stops more than
     * a spacing short of it, is legal for an image that is not the whole array, so
     * WARN: on a whole-array image it means DELTA_SMPL_PAIRS is not the segments'
     * spacing in the image -- typically segment overlap the image trims, which puts
     * columns in the wrong pair, worse toward the far edge.
     *
     * <p>The pairs are segments of one linear array, so along the array axis (the
     * one with the larger extent) every pair must run the same way and each must
     * start beyond the last. A pair running backwards is a segment described
     * mirror-image, and one out of order is a segment in the wrong place -- WARN,
     * since a producer's axis choice is not something this can know for certain.
     */
    static List<NeiFinding> cssfabPairFindings(final String where, final Map<String, String> f,
            final long width, final String widthName) {
        List<NeiFinding> out = new ArrayList<>();
        if (!"S".equals(trim(f.get("SENSOR_TYPE")))) {
            return out;
        }
        Integer n = parseInt(f.get("NUM_FA_PAIRS"));
        Double first = parseDouble(f.get("SMPL_NUM_FIRST"));
        Double delta = parseDouble(f.get("DELTA_SMPL_PAIRS"));
        if (n == null || n < 1 || first == null || delta == null) {
            return out;
        }

        if (width > 0 && delta > 0) {
            double lastStart = first + (n - 1) * delta;
            double end = first + n * delta;
            String grid = "SMPL_NUM_FIRST " + trimNumber(first) + " + " + n + " x DELTA_SMPL_PAIRS "
                    + trimNumber(delta);
            if (lastStart >= width) {
                out.add(NeiFinding.error("CSSFAB-PAIRS-PAST-IMAGE", where, "CSSFAB.DELTA_SMPL_PAIRS",
                        "last pair starts at column " + trimNumber(lastStart),
                        "< " + widthName + " " + width,
                        grid + ": the last field-alignment pair describes no column of the image"));
            } else if (end > width) {
                out.add(NeiFinding.warn("CSSFAB-PAIRS-OVERRUN", where, "CSSFAB.DELTA_SMPL_PAIRS",
                        "grid ends at column " + trimNumber(end), "<= " + widthName + " " + width,
                        grid + " runs " + trimNumber(end - width) + " columns past the image; on "
                                + "a whole-array image DELTA_SMPL_PAIRS is wider than the segments' "
                                + "spacing in it (overlap the image trims?)"));
            } else if (end + delta < width) {
                out.add(NeiFinding.warn("CSSFAB-PAIRS-SHORT", where, "CSSFAB.DELTA_SMPL_PAIRS",
                        "grid ends at column " + trimNumber(end),
                        "within one spacing of " + widthName + " " + width,
                        grid + " leaves " + trimNumber(width - end) + " columns past its last "
                                + "pair; on a whole-array image a pair is missing or the spacing "
                                + "is too narrow"));
            }
        }

        double[][] pairs = new double[n][4];   // start x, start y, end x, end y
        for (int i = 0; i < n; i++) {
            String p = "FIELD_ALIGNMENT[" + i + "].";
            String[] names = {"START_FALIGN_X", "START_FALIGN_Y", "END_FALIGN_X", "END_FALIGN_Y"};
            for (int k = 0; k < 4; k++) {
                Double v = parseDouble(f.get(p + names[k]));
                if (v == null) {
                    return out;
                }
                pairs[i][k] = v;
            }
        }
        double extentX = 0;
        double extentY = 0;
        for (double[] pr : pairs) {
            extentX += Math.abs(pr[2] - pr[0]);
            extentY += Math.abs(pr[3] - pr[1]);
        }
        int axis = extentX >= extentY ? 0 : 1;
        String axisName = axis == 0 ? "X" : "Y";
        double sign = Math.signum(pairs[0][axis + 2] - pairs[0][axis]);
        if (sign == 0 || n < 2) {
            return out;
        }
        List<Integer> backwards = new ArrayList<>();
        List<Integer> outOfOrder = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (sign * (pairs[i][axis + 2] - pairs[i][axis]) <= 0) {
                backwards.add(i + 1);
            }
            if (i > 0 && sign * (pairs[i][axis] - pairs[i - 1][axis]) <= 0) {
                outOfOrder.add(i + 1);
            }
        }
        if (!backwards.isEmpty()) {
            out.add(NeiFinding.warn("CSSFAB-FALIGN-REVERSED", where, "CSSFAB.FALIGN_" + axisName,
                    "pair(s) " + backwards, "every pair running the way pair 1 does",
                    backwards.size() + " of " + n + " segments are described mirror-image along "
                            + "the array"));
        }
        if (!outOfOrder.isEmpty()) {
            out.add(NeiFinding.warn("CSSFAB-FALIGN-OUT-OF-ORDER", where, "CSSFAB.START_FALIGN_" + axisName,
                    "pair(s) " + outOfOrder, "each pair starting beyond the one before it",
                    "the segments do not step across the array in one direction"));
        }
        return out;
    }

    private static boolean near(final double a, final double b) {
        return Math.abs(a - b) < GRID_EPS;
    }

    /** Distance to the nearest whole number, so -0.0000001 and 0.9999999 both read as 0. */
    private static double frac(final double v) {
        return Math.abs(v - Math.rint(v));
    }

    private static String trimNumber(final double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String ascii(final byte[] raw, final int offset, final int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = offset; i < offset + length; i++) {
            text.append((char) (raw[i] & 0xFF));
        }
        return text.toString();
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
