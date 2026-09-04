package org.codice.imaging.nitf.nei;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Parses the NEI TREs and GLAS/GFM DES payloads without modifying imaging-nitf core. */
public final class NeiExtensionParser {
    private static final Set<String> SUPPORTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CSEXRB", "CSRLSB", "CSWRPB", "PIXQLA",
            "CSATTB", "CSEPHB", "CSSFAB", "CSCSDB")));

    public Set<String> supportedTypes() {
        return SUPPORTED;
    }

    public boolean supports(final String type) {
        return type != null && SUPPORTED.contains(type.trim());
    }

    public NeiRecord parse(final String type, final byte[] payload) {
        return parse(type, 1, payload);
    }

    public NeiRecord parse(final String type, final int desVersion, final byte[] payload) {
        if (type == null || payload == null) {
            throw new IllegalArgumentException("type and payload are required");
        }
        String id = type.trim();
        FixedWidthReader r = new FixedWidthReader(id, payload);
        switch (id) {
            case "CSEXRB":
                parseCsexrb(r);
                break;
            case "CSRLSB":
                parseCsrlsb(r);
                break;
            case "PIXQLA":
                parsePixqla(r);
                break;
            case "CSWRPB":
                parseCswrpb(r);
                break;
            case "CSATTB":
                parseCsattb(r, desVersion);
                break;
            case "CSEPHB":
                parseCsephb(r, desVersion);
                break;
            case "CSSFAB":
                parseCssfab(r);
                break;
            case "CSCSDB":
                parseCscsdb(r);
                break;
            default:
                throw new IllegalArgumentException("Unsupported NEI extension: " + id);
        }
        return r.record();
    }

    private static String at(final String prefix, final String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }

    private static String indexed(final String prefix, final String name, final int index) {
        return at(prefix, name) + "[" + index + "]";
    }

    private static boolean is(final String value, final String expected) {
        return expected.equals(value.trim());
    }

    private static void parseCsexrb(final FixedWidthReader r) {
        r.text("IMAGE_UUID", 36);
        int associations = r.integer("NUM_ASSOC_DES", 3);
        for (int i = 0; i < associations; i++) {
            r.text(indexed("", "ASSOC_DES", i) + ".ASSOC_DES_UUID", 36);
        }
        r.text("PLATFORM_ID", 6);
        r.text("PAYLOAD_ID", 6);
        r.text("SENSOR_ID", 6);
        String sensorType = r.text("SENSOR_TYPE", 1);
        r.text("GROUND_REF_POINT_X", 12);
        r.text("GROUND_REF_POINT_Y", 12);
        r.text("GROUND_REF_POINT_Z", 12);
        if (is(sensorType, "S")) {
            r.text("DAY_FIRST_LINE_IMAGE", 8);
            r.text("TIME_FIRST_LINE_IMAGE", 15);
            r.text("TIME_IMAGE_DURATION", 16);
        } else if (is(sensorType, "F")) {
            String location = r.text("TIME_STAMP_LOC", 1);
            if (is(location, "0")) {
                r.text("REFERENCE_FRAME", 9);
                r.text("BASE_TIMESTAMP", 24);
                r.text("DT_MULTIPLIER", 8);
                int dtSize = r.integer("DT_SIZE", 1);
                r.text("NUMBER_FRAMES", 4);
                int numberDt = r.integer("NUMBER_DT", 4);
                for (int i = 0; i < numberDt; i++) {
                    r.text(indexed("", "DELTA_TIME", i) + ".DT", dtSize);
                }
            }
        }
        for (String name : Arrays.asList("MAX_GSD", "ALONG_SCAN_GSD", "CROSS_SCAN_GSD",
                "GEO_MEAN_GSD", "A_S_VERT_GSD", "C_S_VERT_GSD", "GEO_MEAN_VERT_GSD")) {
            r.text(name, 12);
        }
        r.text("GSD_BETA_ANGLE", 5);
        r.text("DYNAMIC_RANGE", 5);
        r.text("NUM_LINES", 7);
        r.text("NUM_SAMPLES", 5);
        r.text("ANGLE_TO_NORTH", 7);
        r.text("OBLIQUITY_ANGLE", 6);
        r.text("AZ_OF_OBLIQUITY", 7);
        r.text("ATM_REFR_FLAG", 1);
        r.text("VEL_ABER_FLAG", 1);
        r.text("GRD_COVER", 1);
        r.text("SNOW_DEPTH_CATEGORY", 1);
        r.text("SUN_AZIMUTH", 7);
        r.text("SUN_ELEVATION", 7);
        r.text("PREDICTED_NIIRS", 3);
        r.text("CIRCL_ERR", 5);
        r.text("LINEAR_ERR", 5);
        r.text("CLOUD_COVER", 3);
        if (is(sensorType, "F")) {
            r.text("ROLLING_SHUTTER_FLAG", 1);
        }
        r.text("UE_TIME_FLAG", 1);
        int reserved = r.integer("RESERVED_LEN", 5);
        r.finishReserved("RESERVED", reserved);
    }

    private static void parseCsrlsb(final FixedWidthReader r) {
        int rows = r.integer("N_RS_ROW_BLOCKS", 2);
        int columns = r.integer("M_RS_COLUMN_BLOCKS", 2);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                String p = "ROW_BLOCK[" + row + "].COLUMN_BLOCK[" + column + "]";
                for (int corner = 1; corner <= 4; corner++) {
                    r.text(at(p, "RS_DT_" + corner), 12);
                }
            }
        }
    }

    private static void parsePixqla(final FixedWidthReader r) {
        String numAis = r.text("NUMAIS", 3);
        if (!is(numAis, "ALL")) {
            int count;
            try {
                count = Integer.parseInt(numAis.trim());
            } catch (NumberFormatException e) {
                throw new NeiFormatException("PIXQLA NUMAIS is not ALL or numeric: " + numAis, e);
            }
            for (int i = 0; i < count; i++) {
                r.text(indexed("", "ASSOCIATED_IMAGE_SEGMENT", i) + ".AISDLVL", 3);
            }
        }
        int qualityCount = r.integer("NPIXQUAL", 4);
        r.text("PQ_BIT_VALUE", 1);
        for (int i = 0; i < qualityCount; i++) {
            r.text(indexed("", "PIXEL_CONDITION", i) + ".PQ_CONDITION", 40);
        }
    }

    private static void parseCswrpb(final FixedWidthReader r) {
        int sets = r.integer("NUM_SETS_WARP_DATA", 1);
        String sensorType = r.text("SENSOR_TYPE", 1);
        if (is(sensorType, "F")) {
            r.text("WRP_INTERP", 1);
        }
        for (int set = 0; set < sets; set++) {
            String p = indexed("", "WARP_DATA", set);
            if (is(sensorType, "F")) {
                r.text(at(p, "FL_WARP"), 11);
            }
            for (String name : Arrays.asList("OFFSET_LINE", "OFFSET_SAMP", "SCALE_LINE",
                    "SCALE_SAMP", "OFFSET_LINE_UNWRP", "OFFSET_SAMP_UNWRP",
                    "SCALE_LINE_UNWRP", "SCALE_SAMP_UNWRP")) {
                r.text(at(p, name), 7);
            }
            int m1 = r.integer(at(p, "LINE_POLY_ORDER_M1"), 1);
            int m2 = r.integer(at(p, "LINE_POLY_ORDER_M2"), 1);
            int n1 = r.integer(at(p, "SAMP_POLY_ORDER_N1"), 1);
            int n2 = r.integer(at(p, "SAMP_POLY_ORDER_N2"), 1);
            for (int j = 0; j <= m2; j++) {
                for (int i = 0; i <= m1; i++) {
                    r.text(at(p, "A[" + j + "][" + i + "]"), 21);
                }
            }
            for (int j = 0; j <= n2; j++) {
                for (int i = 0; i <= n1; i++) {
                    r.text(at(p, "B[" + j + "][" + i + "]"), 21);
                }
            }
        }
        int reserved = r.integer("RESERVED_LEN", 5);
        r.finishReserved("RESERVED", reserved);
    }

    private static void parseCsattb(final FixedWidthReader r, final int desVersion) {
        r.text("QUAL_FLAG_ATT", 1);
        String interpolation = r.text("INTERP_TYPE_ATT", 1);
        if (is(interpolation, "2") || is(interpolation, "3")) {
            r.text("INTERP_ORDER_ATT", 1);
        }
        r.text("ATT_TYPE", 1);
        String frame = r.text("ECI_ECF_ATT", 1);
        if (is(frame, "0") && desVersion >= 2) {
            parseEarthOrientation(r);
        }
        r.text("DT_ATT", 13);
        r.text("DATE_ATT", 8);
        r.text("T0_ATT", 16);
        int count = r.integer("NUM_ATT", 5);
        for (int i = 0; i < count; i++) {
            String p = indexed("", "ATTITUDE", i);
            r.text(at(p, "Q1"), 18);
            r.text(at(p, "Q2"), 18);
            r.text(at(p, "Q3"), 18);
            r.text(at(p, "Q4"), 18);
        }
        int reserved = r.integer("RESERVED_LEN", 9);
        r.finishReserved("RESERVED", reserved);
    }

    private static void parseCsephb(final FixedWidthReader r, final int desVersion) {
        r.text("QUAL_FLAG_EPH", 1);
        String interpolation = r.text("INTERP_TYPE_EPH", 1);
        if (is(interpolation, "2")) {
            r.text("INTERP_ORDER_EPH", 1);
        }
        r.text("EPHEM_FLAG", 1);
        String frame = r.text("ECI_ECF_EPHEM", 1);
        if (is(frame, "0") && desVersion >= 2) {
            parseEarthOrientation(r);
        }
        r.text("DT_EPHEM", 13);
        r.text("DATE_EPHEM", 8);
        r.text("T0_EPHEM", 16);
        int count = r.integer("NUM_EPHEM", 5);
        for (int i = 0; i < count; i++) {
            String p = indexed("", "EPHEM", i);
            r.text(at(p, "EPHEM_X"), 12);
            r.text(at(p, "EPHEM_Y"), 12);
            r.text(at(p, "EPHEM_Z"), 12);
        }
        int reserved = r.integer("RESERVED_LEN", 9);
        if (reserved != 0) {
            int start = r.position();
            int maskLength = r.integer("MASK_LEN", 2);
            r.text("RESERVED_FIELD_MASK", maskLength);
            r.text("RESERVED_LEN_AREA1", 9);
            String acceleration = r.text("ACCEL_PROVIDED", 1);
            for (int i = 0; i < count; i++) {
                String p = indexed("", "VELOCITY", i);
                r.text(at(p, "VEL_X"), 12);
                r.text(at(p, "VEL_Y"), 12);
                r.text(at(p, "VEL_Z"), 12);
                if (is(acceleration, "Y")) {
                    r.text(at(p, "ACCEL_X"), 12);
                    r.text(at(p, "ACCEL_Y"), 12);
                    r.text(at(p, "ACCEL_Z"), 12);
                }
            }
            int consumed = r.position() - start;
            if (consumed < reserved) {
                r.text("RESERVED_AREA_REMAINDER", reserved - consumed);
            } else if (consumed > reserved) {
                throw new NeiFormatException("CSEPHB reserved area exceeded RESERVED_LEN");
            }
        }
    }

    private static void parseEarthOrientation(final FixedWidthReader r) {
        r.text("TA_POLE", 19);
        for (String name : Arrays.asList("A_POLE", "B_POLE", "CJ1_POLE", "CJ2_POLE",
                "DJ1_POLE", "DJ2_POLE")) {
            r.text(name, 11);
        }
        r.text("PJ1_POLE", 10);
        r.text("PJ2_POLE", 10);
        for (String name : Arrays.asList("E_POLE", "F_POLE", "GK1_POLE", "GK2_POLE",
                "HK1_POLE", "HK2_POLE")) {
            r.text(name, 11);
        }
        r.text("PK1_POLE", 10);
        r.text("PK2_POLE", 10);
        r.text("TB_UT", 19);
        for (String name : Arrays.asList("I_UT", "J_UT", "KN1_UT", "KN2_UT", "KN3_UT",
                "KN4_UT", "LN1_UT", "LN2_UT", "LN3_UT", "LN4_UT")) {
            r.text(name, 12);
        }
        r.text("PN1_UT", 10);
        r.text("PN2_UT", 10);
        r.text("PN3_UT", 10);
        r.text("PN4_UT", 10);
    }

    private static void parseCssfab(final FixedWidthReader r) {
        // One Maxar producer emits a 173-byte legacy preamble ahead of the registered
        // CSSFAB body. Keep it visible rather than silently discarding it.
        if (r.peek(0) != 'S' && r.peek(0) != 'F') {
            int bodyOffset = -1;
            for (int i = 1; i + 4 < r.remaining(); i++) {
                if ((r.peek(i) == 'S' || r.peek(i) == 'F') && r.peek(i + 1) == ' '
                        && Character.isDigit(r.peek(i + 2)) && Character.isDigit(r.peek(i + 3))) {
                    bodyOffset = i;
                    break;
                }
            }
            if (bodyOffset < 0) {
                throw new NeiFormatException("CSSFAB body start not found");
            }
            r.text("VENDOR_PREAMBLE", bodyOffset);
        }
        String sensorType = r.text("SENSOR_TYPE", 1);
        r.text("BAND_TYPE", 1);
        r.text("BAND_WAVELENGTH", 11);
        int bands = r.integer("N_BANDS", 5);
        for (int i = 0; i < bands; i++) {
            String p = indexed("", "BAND", i);
            r.text(at(p, "BAND_INDEX"), 5);
            r.text(at(p, "IREPBAND"), 2);
            r.text(at(p, "ISUBCAT"), 6);
        }
        int focalPoints = r.integer("NUM_FL_PTS", 3);
        r.text("FL_INTERP", 1);
        r.text("FOC_LENGTH_DATE", 8);
        for (int i = 0; i < focalPoints; i++) {
            String p = indexed("", "FOCAL_LENGTH", i);
            r.text(at(p, "FOC_LENGTH_TIME"), 15);
            r.text(at(p, "FOC_LENGTH"), 11);
        }
        for (String name : Arrays.asList("PPOFF_X", "PPOFF_Y", "PPOFF_Z", "ANGOFF_X",
                "ANGOFF_Y", "ANGOFF_Z")) {
            r.text(name, 10);
        }
        if (is(sensorType, "S")) {
            r.text("SMPL_NUM_FIRST", 12);
            r.text("DELTA_SMPL_PAIRS", 11);
            int pairs = r.integer("NUM_FA_PAIRS", 3);
            for (int i = 0; i < pairs; i++) {
                String p = indexed("", "FIELD_ALIGNMENT", i);
                r.text(at(p, "START_FALIGN_X"), 11);
                r.text(at(p, "START_FALIGN_Y"), 11);
                r.text(at(p, "END_FALIGN_X"), 11);
                r.text(at(p, "END_FALIGN_Y"), 11);
            }
        }
        int reserved = r.integer("RESERVED_LEN", 9);
        r.finishReserved("RESERVED", reserved);
    }

    private static int triangle(final int n) {
        return n * (n + 1) / 2;
    }

    private static void parseCscsdb(final FixedWidthReader r) {
        r.text("COV_VERSION_DATE", 8);
        int coreSets = r.integer("CORE_SETS", 1);
        for (int core = 0; core < coreSets; core++) {
            String cp = indexed("", "CORE_SET", core);
            r.text(at(cp, "REF_FRAME_POSITION"), 1);
            r.text(at(cp, "REF_FRAME_ATTITUDE"), 1);
            int groups = r.integer(at(cp, "NUM_GROUPS"), 1);
            for (int group = 0; group < groups; group++) {
                parseCovarianceGroup(r, indexed(cp, "GROUP", group));
            }
        }
        parseInteriorOrientation(r);
        parseTimeSynchronization(r);
        parseUnmodeledError(r);
        parseCorrelationFunctions(r);
        String direct = r.text("DIRECT_COVARIANCE_FLAG", 1);
        if (is(direct, "1")) {
            String type = r.text("DC_TYPE", 1);
            if (is(type, "0")) {
                int count = r.integer("NUM_PARA", 4);
                for (int i = 0; i < count; i++) {
                    r.text(indexed("", "DIRECT_ADJ", i) + ".ADJ", 21);
                }
                for (int i = 0; i < triangle(count); i++) {
                    r.text(indexed("", "DIRECT_ERRCOV", i) + ".ERRCOV_C4", 21);
                }
            }
        }
        int reserved = r.integer("RESERVED_LEN", 9);
        r.finishReserved("RESERVED", reserved);
    }

    private static void parseCovarianceGroup(final FixedWidthReader r, final String p) {
        r.text(at(p, "CORR_REF_DATE"), 8);
        r.text(at(p, "CORR_REF_TIME"), 16);
        int parameters = r.integer(at(p, "NUM_ADJ_PARM"), 1);
        for (int i = 0; i < parameters; i++) {
            r.text(indexed(p, "ADJ_PARM", i) + ".ADJ_PARM_ID", 1);
        }
        String basic = r.text(at(p, "BASIC_SUB_ALLOC"), 1);
        if (is(basic, "1")) {
            for (int i = 0; i < triangle(parameters); i++) {
                r.text(indexed(p, "BASIC_ERRCOV", i) + ".ERRCOV_C1", 21);
            }
            parsePairingBlock(r, p, "BASIC_PF", "BASIC_PF_FLAG");
            parsePairingBlock(r, p, "BASIC_PL", "BASIC_PL_FLAG");
            String sr = r.text(at(p, "BASIC_SR_FLAG"), 1);
            if (is(sr, "1")) {
                r.text(at(p, "BASIC_SR_SPDCF"), 2);
            }
        }
        String post = r.text(at(p, "POST_SUB_ALLOC"), 1);
        if (is(post, "1")) {
            r.text(at(p, "POST_START_DATE"), 8);
            r.text(at(p, "POST_START_TIME"), 15);
            r.text(at(p, "POST_DT"), 13);
            int posts = r.integer(at(p, "NUM_POSTS"), 3);
            String common = r.text(at(p, "COMMON_POSTS_COV"), 1);
            int covarianceSets = is(common, "1") ? 1 : posts;
            for (int postIndex = 0; postIndex < covarianceSets; postIndex++) {
                String pp = is(common, "1") ? p : indexed(p, "POST", postIndex);
                for (int i = 0; i < triangle(parameters); i++) {
                    r.text(indexed(pp, "POST_ERRCOV", i) + ".ERRCOV_C2", 21);
                }
            }
            r.text(at(p, "POST_INTERP"), 1);
            parsePairingBlock(r, p, "POST_PF", "POST_PF_FLAG");
            parsePairingBlock(r, p, "POST_PL", "POST_PL_FLAG");
            String sr = r.text(at(p, "POST_SR_FLAG"), 1);
            if (is(sr, "1")) {
                r.text(at(p, "POST_SR_SPDCF"), 2);
                r.text(at(p, "POST_CORR"), 1);
            }
        }
    }

    private static void parsePairingBlock(final FixedWidthReader r, final String p,
            final String stem, final String flagName) {
        String flag = r.text(at(p, flagName), 1);
        if (!is(flag, "1")) {
            return;
        }
        int count = r.integer(at(p, "NUM_" + stem), 2);
        for (int i = 0; i < count; i++) {
            String ep = indexed(p, stem, i);
            r.text(at(ep, stem + "_SPDCF"), 2);
            int pairings = r.integer(at(ep, "NUM_PAIRINGS_" + stem), 2);
            for (int j = 0; j < pairings; j++) {
                r.text(indexed(ep, stem + "_PAIRING", j) + "." + stem + "_SPDCF_SENSOR", 6);
            }
        }
    }

    private static void parseInteriorOrientation(final FixedWidthReader r) {
        String present = r.text("IO_CAL_AP", 1);
        if (!is(present, "1")) {
            return;
        }
        int sets = r.integer("NUM_SETS_CAL_AP", 2);
        for (int i = 0; i < sets; i++) {
            r.text(indexed("", "CAL_AP_SET", i) + ".FOCAL_LENGTH_CAL", 11);
        }
        int groups = r.integer("NCAL_CPG", 2);
        for (int group = 0; group < groups; group++) {
            String p = indexed("", "CAL_CPG", group);
            r.text(at(p, "CORR_REF_DATE_IO"), 8);
            r.text(at(p, "CORR_REF_TIME_IO"), 16);
            int parameters = r.integer(at(p, "N1CAL"), 2);
            for (int i = 0; i < parameters; i++) {
                r.text(indexed(p, "CAL_AP_IDS", i) + ".CAL_AP_ID", 2);
            }
            for (int set = 0; set < sets; set++) {
                String sp = indexed(p, "CAL_AP_SET_COV", set);
                for (int i = 0; i < triangle(parameters); i++) {
                    r.text(indexed(sp, "CAL_ERRCOV", i) + ".ERRCOV_C3", 21);
                }
            }
            r.text(at(p, "CAL_INTERP"), 1);
            r.text(at(p, "SPDCF_ID_TIME"), 2);
            r.text(at(p, "SPDCF_ID_FL"), 2);
        }
    }

    private static void parseTimeSynchronization(final FixedWidthReader r) {
        String present = r.text("TS_CAL_AP", 1);
        if (!is(present, "1")) {
            return;
        }
        int group = r.integer("NUM_TS_GRP", 1);
        switch (group) {
            case 1:
                r.text("CORR_REF_DATE_TS", 8);
                r.text("CORR_REF_TIME_TS", 16);
                r.text("TSRR", 21);
                r.text("TSRC", 21);
                r.text("TSCC", 21);
                r.text("TS_SPDCF", 2);
                break;
            case 2:
                timeCovariance(r, "TSP", "TS_POS_COV", "TS_POS_SPDCF");
                timeCovariance(r, "TSA", "TS_ATT_COV", "TS_ATT_SPDCF");
                break;
            case 3:
                r.text("CORR_REF_DATE_TS", 8);
                r.text("CORR_REF_TIME_TS", 16);
                for (String name : Arrays.asList("TS_POS_COV", "TS_POS_ATT_COV", "TS_POS_FL_COV",
                        "TS_ATT_COV", "TS_ATT_FL_COV", "TS_FL_COV")) {
                    r.text(name, 21);
                }
                r.text("TS_SPDCF", 2);
                break;
            case 4:
                r.text("CORR_REF_DATE_TSPA", 8);
                r.text("CORR_REF_TIME_TSPA", 16);
                r.text("TS_POS_COV", 21);
                r.text("TS_POS_ATT_COV", 21);
                r.text("TS_ATT_COV", 21);
                r.text("TS_PA_SPDCF", 2);
                timeCovariance(r, "TSFL", "TS_FL_COV", "TS_FL_SPDCF");
                break;
            case 5:
                timeCovariance(r, "TSP", "TS_POS_COV", "TS_POS_SPDCF");
                timeCovariance(r, "TSA", "TS_ATT_COV", "TS_ATT_SPDCF");
                timeCovariance(r, "TSFL", "TS_FL_COV", "TS_FL_SPDCF");
                break;
            default:
                throw new NeiFormatException("CSCSDB unsupported NUM_TS_GRP: " + group);
        }
    }

    private static void timeCovariance(final FixedWidthReader r, final String suffix,
            final String covariance, final String spdcf) {
        r.text("CORR_REF_DATE_" + suffix, 8);
        r.text("CORR_REF_TIME_" + suffix, 16);
        r.text(covariance, 21);
        r.text(spdcf, 2);
    }

    private static void parseUnmodeledError(final FixedWidthReader r) {
        String present = r.text("UE_FLAG", 1);
        if (!is(present, "1")) {
            return;
        }
        int lines = r.integer("LINE_DIMENSION", 3);
        int samples = r.integer("SAMPLE_DIMENSION", 2);
        for (int line = 0; line < lines; line++) {
            for (int sample = 0; sample < samples; sample++) {
                String p = "UE_LINE[" + line + "].UE_SAMPLE[" + sample + "]";
                r.text(at(p, "URR"), 21);
                r.text(at(p, "URC"), 21);
                r.text(at(p, "UCC"), 21);
            }
        }
        r.text("LINE_SPDCF", 2);
        r.text("SAMPLE_SPDCF", 2);
    }

    private static void parseCorrelationFunctions(final FixedWidthReader r) {
        String present = r.text("SPDCF_FLAG", 1);
        if (!is(present, "1")) {
            return;
        }
        int functions = r.integer("NUM_SPDCF", 2);
        for (int function = 0; function < functions; function++) {
            String p = indexed("", "SPDCF", function);
            r.text(at(p, "SPDCF_ID"), 2);
            int constituents = r.integer(at(p, "SPDCF_P"), 2);
            for (int constituent = 0; constituent < constituents; constituent++) {
                String cp = indexed(p, "SPDCF_CONSTITUENT", constituent);
                String family = r.text(at(cp, "SPDCF_FAM"), 1);
                r.text(at(cp, "SPDCF_WEIGHT"), 5);
                if (is(family, "0")) {
                    r.text(at(cp, "FP_A"), 8);
                    r.text(at(cp, "FP_ALPHA"), 8);
                    r.text(at(cp, "FP_BETA"), 9);
                    r.text(at(cp, "FP_T"), 21);
                } else if (is(family, "1")) {
                    int segments = r.integer(at(cp, "NUM_SEGS"), 2);
                    for (int segment = 0; segment < segments; segment++) {
                        String sp = indexed(cp, "SPDCF_SEGMENT", segment);
                        r.text(at(sp, "PL_MAX_COR"), 8);
                        r.text(at(sp, "PL_TAU_MAX_COR"), 21);
                    }
                } else if (is(family, "2")) {
                    r.text(at(cp, "DC_A"), 8);
                    r.text(at(cp, "DC_T"), 21);
                    r.text(at(cp, "DC_P"), 21);
                }
            }
        }
    }
}
