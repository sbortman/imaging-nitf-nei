package com.vantor.nitf.nei;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codice.imaging.nitf.core.common.DateTime;
import org.codice.imaging.nitf.core.common.NitfFormatException;
import org.codice.imaging.nitf.core.header.NitfHeader;
import org.codice.imaging.nitf.core.image.ImageCategory;
import org.codice.imaging.nitf.core.image.ImageCoordinatePair;
import org.codice.imaging.nitf.core.image.ImageCoordinates;
import org.codice.imaging.nitf.core.image.ImageRepresentation;
import org.codice.imaging.nitf.core.image.ImageSegment;
import org.codice.imaging.nitf.core.image.TargetId;
import org.codice.imaging.nitf.core.impl.SlottedParseStrategy;
import org.codice.imaging.nitf.core.security.SecurityClassification;
import org.codice.imaging.nitf.core.security.SecurityMetadata;
import org.codice.imaging.nitf.core.tre.Tre;
import org.codice.imaging.nitf.core.tre.TreCollection;
import org.codice.imaging.nitf.core.tre.TreEntry;
import org.codice.imaging.nitf.core.tre.TreGroup;
import org.codice.imaging.nitf.fluent.NitfSegmentsFlow;
import org.codice.imaging.nitf.fluent.impl.NitfParserInputFlowImpl;

/**
 * Every file-header, image-subheader and decoded TRE field of a NITF as flat, insertion-ordered
 * text maps, read with the stock imaging-nitf 0.10 parser. Pixel data is never read.
 * Values are trimmed; blank values are omitted so "absent" and "blank" mean the same thing.
 */
public final class NitfMetadata {

    private final Map<String, String> file;
    private final List<Map<String, String>> images;

    private NitfMetadata(final Map<String, String> file, final List<Map<String, String>> images) {
        this.file = Collections.unmodifiableMap(file);
        List<Map<String, String>> copies = new ArrayList<>();
        for (Map<String, String> image : images) {
            copies.add(Collections.unmodifiableMap(image));
        }
        this.images = Collections.unmodifiableList(copies);
    }

    public static NitfMetadata read(final File source) throws FileNotFoundException, NitfFormatException {
        Map<String, String> fileFields = new LinkedHashMap<>();
        List<Map<String, String>> images = new ArrayList<>();
        try (InputStream input = Nitf21BlankEncrypInputStream.open(source)) {
            NitfSegmentsFlow flow = new NitfParserInputFlowImpl()
                    .inputStream(input)
                    .build(new SlottedParseStrategy(SlottedParseStrategy.HEADERS_ONLY));
            try {
                flow.fileHeader(header -> readHeader(header, fileFields))
                        .forEachImageSegment(image -> images.add(readImage(image)));
            } finally {
                flow.end();
            }
        } catch (IOException e) {
            throw new NeiFormatException("Could not read " + source, e);
        }
        return new NitfMetadata(fileFields, images);
    }

    /** File-header fields and file-level TREs. */
    public Map<String, String> fileFields() {
        return file;
    }

    /** One map per image segment, in file order (segment prefix not included in the keys). */
    public List<Map<String, String>> imageSegments() {
        return images;
    }

    /** Everything in one map: file fields bare, segment fields as image&lt;N&gt;.KEY. */
    public Map<String, String> flat() {
        Map<String, String> all = new LinkedHashMap<>(file);
        for (int i = 0; i < images.size(); i++) {
            for (Map.Entry<String, String> entry : images.get(i).entrySet()) {
                all.put("image" + i + "." + entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(all);
    }

    private static void readHeader(final NitfHeader header, final Map<String, String> target) {
        put(target, "FTITLE", header.getFileTitle());
        put(target, "OSTAID", header.getOriginatingStationId());
        put(target, "ONAME", header.getOriginatorsName());
        put(target, "FDT", source(header.getFileDateTime()));
        putSecurity(target, "FS", header.getFileSecurityMetadata());
        putTres(target, header.getTREsRawStructure());
    }

    private static Map<String, String> readImage(final ImageSegment image) {
        Map<String, String> target = new LinkedHashMap<>();
        put(target, "IID1", image.getIdentifier());
        put(target, "IID2", image.getImageIdentifier2());
        put(target, "IDATIM", source(image.getImageDateTime()));
        put(target, "ISORCE", image.getImageSource());
        TargetId targetId = image.getImageTargetId();
        if (targetId != null) {
            put(target, "TGTID.BE", targetId.getBasicEncyclopediaNumber());
            put(target, "TGTID.OSUFFIX", targetId.getOSuffix());
            put(target, "TGTID.COUNTRY", targetId.getCountryCode());
        }
        putSecurity(target, "IS", image.getSecurityMetadata());
        put(target, "NROWS", Long.toString(image.getNumberOfRows()));
        put(target, "NCOLS", Long.toString(image.getNumberOfColumns()));
        put(target, "NBANDS", Integer.toString(image.getNumBands()));
        put(target, "ABPP", Integer.toString(image.getActualBitsPerPixelPerBand()));
        ImageRepresentation irep = image.getImageRepresentation();
        if (irep != null && irep != ImageRepresentation.UNKNOWN) {
            put(target, "IREP", irep.getTextEquivalent());
        }
        ImageCategory icat = image.getImageCategory();
        if (icat != null && icat != ImageCategory.UNKNOWN) {
            put(target, "ICAT", icat.getTextEquivalent());
        }
        ImageCoordinates corners = image.getImageCoordinates();
        if (corners != null) {
            put(target, "IGEOLO", pair(corners.getCoordinate00()) + " " + pair(corners.getCoordinate0MaxCol()) + " "
                    + pair(corners.getCoordinateMaxRowMaxCol()) + " " + pair(corners.getCoordinateMaxRow0()));
        }
        putTres(target, image.getTREsRawStructure());
        return target;
    }

    private static String pair(final ImageCoordinatePair pair) {
        return pair == null ? "" : pair.getLatitude() + "," + pair.getLongitude();
    }

    private static void putSecurity(final Map<String, String> target, final String prefix, final SecurityMetadata security) {
        if (security == null) {
            return;
        }
        SecurityClassification classification = security.getSecurityClassification();
        if (classification != null && classification != SecurityClassification.UNKNOWN) {
            put(target, prefix + "CLAS", classification.getTextEquivalent());
        }
        put(target, prefix + "CLSY", security.getSecurityClassificationSystem());
        put(target, prefix + "CODE", security.getCodewords());
        put(target, prefix + "CTLH", security.getControlAndHandling());
        put(target, prefix + "REL", security.getReleaseInstructions());
    }

    private static void putTres(final Map<String, String> target, final TreCollection tres) {
        if (tres == null) {
            return;
        }
        Map<String, Integer> seen = new HashMap<>();
        for (Tre tre : tres.getTREs()) {
            String name = tre.getName().trim();
            int occurrence = seen.merge(name, 1, Integer::sum);
            String prefix = occurrence == 1 ? name : name + "#" + occurrence;
            // TREs imaging-nitf does not know stay raw (NEI extensions are decoded by NeiNitfAdapter)
            if (tre.getRawData() == null) {
                putEntries(target, prefix, tre.getEntries());
            }
        }
    }

    private static void putEntries(final Map<String, String> target, final String prefix, final List<TreEntry> entries) {
        if (entries == null) {
            return;
        }
        for (TreEntry entry : entries) {
            String key = prefix + "." + entry.getName().trim();
            List<TreGroup> groups = entry.getGroups();
            if (groups != null && !groups.isEmpty()) {
                for (int i = 0; i < groups.size(); i++) {
                    putEntries(target, key + "[" + i + "]", groups.get(i).getEntries());
                }
            } else {
                put(target, key, entry.getFieldValue());
            }
        }
    }

    private static String source(final DateTime dateTime) {
        return dateTime == null ? null : dateTime.getSourceString();
    }

    private static void put(final Map<String, String> target, final String key, final String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            target.put(key, trimmed);
        }
    }
}
