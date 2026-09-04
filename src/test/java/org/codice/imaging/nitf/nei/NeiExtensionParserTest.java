package org.codice.imaging.nitf.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class NeiExtensionParserTest {
    private final NeiExtensionParser parser = new NeiExtensionParser();

    @Test
    public void parsesCsexrbWithAssociations() {
        StringBuilder p = new StringBuilder();
        p.append("eea7076f-7c64-4620-b772-ac813866a92f").append("002");
        p.append("1aa17a53-1c60-4fab-945b-5d12416ca822");
        p.append("d4c6f40e-5597-49b7-8254-c7fcee59b328");
        p.append(pad("WV", 6)).append(pad("03", 6)).append(pad("PAN", 6)).append("S");
        p.append(spaces(36));
        p.append("20240607").append("85800.000000000").append("+00003.413589666");
        p.append(spaces(84)).append(spaces(10));
        p.append("0000000").append("00000");
        p.append(spaces(20)).append("  ").append("99");
        p.append(spaces(14)).append(spaces(3)).append(spaces(10)).append(spaces(3));
        p.append(" ").append("00000");

        NeiRecord record = parse("CSEXRB", p);
        assertEquals(2, record.getInt("NUM_ASSOC_DES"));
        assertEquals("WV", record.get("PLATFORM_ID").trim());
        assertEquals("20240607", record.get("DAY_FIRST_LINE_IMAGE"));
        assertEquals("d4c6f40e-5597-49b7-8254-c7fcee59b328",
                record.get("ASSOC_DES[1].ASSOC_DES_UUID"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesCsrlsbGrid() {
        StringBuilder p = new StringBuilder("0202");
        for (int block = 1; block <= 4; block++) {
            for (int corner = 1; corner <= 4; corner++) {
                p.append(String.format("+0000000%d.%d0", block, corner));
            }
        }
        NeiRecord record = parse("CSRLSB", p);
        assertEquals("+00000004.40",
                record.get("ROW_BLOCK[1].COLUMN_BLOCK[1].RS_DT_4"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesPixqlaAssociatedImages() {
        StringBuilder p = new StringBuilder("00200300700021");
        p.append(pad("Cloud", 40)).append(pad("Sensor_Saturated", 40));
        NeiRecord record = parse("PIXQLA", p);
        assertEquals("007", record.get("ASSOCIATED_IMAGE_SEGMENT[1].AISDLVL"));
        assertEquals(2, record.getInt("NPIXQUAL"));
        assertEquals("Sensor_Saturated",
                record.get("PIXEL_CONDITION[1].PQ_CONDITION").trim());
    }

    @Test
    public void parsesInclusiveCswrpbPolynomialOrders() {
        StringBuilder p = new StringBuilder("1F1");
        p.append("00.12345678");
        for (int i = 0; i < 8; i++) {
            p.append("0000512");
        }
        p.append("1200");
        for (int j = 0; j <= 2; j++) {
            for (int i = 0; i <= 1; i++) {
                p.append(String.format("+%d.00000000000000E+0%d", i, j));
            }
        }
        p.append("+9.99999999999999E+01").append("00000");
        NeiRecord record = parse("CSWRPB", p);
        assertEquals("+1.00000000000000E+02", record.get("WARP_DATA[0].A[2][1]"));
        assertEquals("+9.99999999999999E+01", record.get("WARP_DATA[0].B[0][0]"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesCsephbVectorsAndVelocity() {
        StringBuilder p = new StringBuilder("12321");
        p.append("000.02000000020240607235000.20000000000002");
        p.append("-04650413.20+01029613.77+05110465.09");
        p.append("-04651390.88+01030212.19+05109457.17");
        p.append("00000008501").append("1").append("000000073").append("N");
        p.append("-00004888.95+00002992.17-00005039.01");
        p.append("-00004886.20+00002990.05-00005041.55");
        NeiRecord record = parse("CSEPHB", p);
        assertEquals(2, record.getInt("NUM_EPHEM"));
        assertEquals("-00004888.95", record.get("VELOCITY[0].VEL_X"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesCsattbQuaternion() {
        String payload = "02320" + "000.020000000" + "20240607" + "235000.300000000"
                + "00001" + "-0.180248530324575" + "-0.838233121509416"
                + "-0.409883760738082" + "-0.311241070560455" + "000000000";
        NeiRecord record = parser.parse("CSATTB", 1,
                payload.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(1, record.getInt("NUM_ATT"));
        assertEquals("-0.311241070560455", record.get("ATTITUDE[0].Q4"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesCssfabLineScanner() {
        StringBuilder p = new StringBuilder("S 00.0000000000000");
        p.append("0011").append("20240607");
        p.append("85800.000000000").append("16.00000000");
        p.append("+00.000000+00.000000+00.000000");
        p.append("+0.0000000+0.0000000+0.0000000");
        p.append("+00000.00000").append("00000.00582").append("001");
        p.append("+00.0000000+00.0000000+00.0058240+00.0005800");
        p.append("000000000");
        NeiRecord record = parse("CSSFAB", p);
        assertEquals(1, record.getInt("NUM_FA_PAIRS"));
        assertEquals("+00.0058240", record.get("FIELD_ALIGNMENT[0].END_FALIGN_X"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void retainsMaxarCssfabPreamble() {
        StringBuilder p = new StringBuilder(spaces(173));
        p.append("S 00.00000000000000011        00000.00000000000.00000000000.000000000"
                + ".00000000.000000000.000000000.0000000-1.5707963000000.0000000000"
                + ".00000000000000000");
        NeiRecord record = parse("CSSFAB", p);
        assertEquals(173, record.get("VENDOR_PREAMBLE").length());
        assertEquals("-1.5707963", record.get("ANGOFF_Z"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void parsesCscsdbFormulaAndCorrelationBranches() {
        StringBuilder p = new StringBuilder("20240501112");
        p.append("1");
        p.append("20000101").append("000000.000000000").append("0");
        p.append("1").append("0").append("0").append("1").append("00");
        p.append("1").append("00000000").append("00000.000000000")
                .append("000.000000000").append("000").append("1");
        p.append("1").append("0").append("0").append("1").append("00").append("0");
        p.append("0").append("0");
        p.append("1").append("000").append("00").append("01").append("02");
        p.append("1").append("01").append("00").append("01");
        p.append("0").append("1.000").append("1.000000").append("0.100000")
                .append("10.000000").append("0.000000000000000E+00");
        p.append("0").append("000000000");

        NeiRecord record = parse("CSCSDB", p);
        assertEquals(1, record.getInt("CORE_SETS"));
        assertEquals("00", record.get("CORE_SET[0].GROUP[0].POST_SR_SPDCF"));
        assertEquals("10.000000", record.get("SPDCF[0].SPDCF_CONSTITUENT[0].FP_BETA"));
        assertEquals(0, record.getRemainingBytes());
    }

    @Test
    public void reportsBoundsWithFieldAndOffset() {
        try {
            parser.parse("PIXQLA", "ALL0001".getBytes(StandardCharsets.ISO_8859_1));
        } catch (NeiFormatException e) {
            assertTrue(e.getMessage().contains("PQ_BIT_VALUE"));
            assertTrue(e.getMessage().contains("offset 7"));
            return;
        }
        throw new AssertionError("Expected bounds failure");
    }

    private NeiRecord parse(final String type, final CharSequence payload) {
        return parser.parse(type, payload.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String pad(final String value, final int width) {
        return String.format("%-" + width + "s", value);
    }

    private static String spaces(final int count) {
        return pad("", count);
    }
}
