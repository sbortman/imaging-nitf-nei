package org.codice.imaging.nitf.nei;

import java.io.File;
import java.util.Map;

/** Tiny command-line smoke test for the stock-jar adapter. */
public final class NeiInfo {
    private NeiInfo() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: mvn exec:java -Dexec.args=/path/to/file.ntf");
            System.exit(2);
        }
        File file = new File(args[0]);
        NeiScanReport report = new NeiNitfAdapter().scanReport(file);
        System.out.printf("%s: %d NEI record(s), %d parse failure(s)%n", file,
                report.getRecords().size(), report.getFailures().size());
        for (NeiOccurrence occurrence : report.getRecords()) {
            NeiRecord record = occurrence.getRecord();
            System.out.printf("%s %d: %s (%d fields, %d/%d bytes)%n",
                    occurrence.getContainer(), occurrence.getSegmentIndex(), record.getType(),
                    record.getFields().size(), record.getBytesConsumed(), record.getPayloadLength());
            for (Map.Entry<String, String> field : record.getFields().entrySet()) {
                System.out.printf("  %-64s %s%n", field.getKey(), field.getValue().trim());
            }
        }
        for (NeiParseFailure failure : report.getFailures()) {
            System.out.printf("FAILED %s %d: %s: %s%n", failure.getContainer(),
                    failure.getSegmentIndex(), failure.getType(), failure.getMessage());
        }
    }
}
