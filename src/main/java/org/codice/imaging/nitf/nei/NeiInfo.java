package org.codice.imaging.nitf.nei;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tiny command-line smoke test for the stock-jar adapter. */
public final class NeiInfo {
    private NeiInfo() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        String path = null;
        boolean json = false;
        boolean showTres = true;
        for (String argument : args) {
            switch (argument) {
                case "--json":
                    json = true;
                    break;
                case "--no-tres":
                    showTres = false;
                    break;
                case "--no-color":
                    // Text output is already uncoloured; accepted for command compatibility.
                    break;
                default:
                    if (argument.startsWith("--") || path != null) {
                        System.err.println("neiinfo: unexpected argument: " + argument);
                        usage();
                        System.exit(2);
                    }
                    path = argument;
            }
        }
        if (path == null) {
            usage();
            System.exit(2);
        }
        File file = new File(path);
        if (!file.isFile()) {
            System.err.println("neiinfo: cannot open '" + path + "'");
            System.exit(1);
        }
        NeiScanReport report = new NeiNitfAdapter().scanReport(file);
        List<NeiOccurrence> records = filteredRecords(report.getRecords(), showTres);
        if (json) {
            System.out.print(JsonOutput.write(file, records, report.getFailures()));
            return;
        }
        System.out.printf("%s: %d NEI record(s), %d parse failure(s)%n", file,
                records.size(), report.getFailures().size());
        for (NeiOccurrence occurrence : records) {
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

    private static List<NeiOccurrence> filteredRecords(final List<NeiOccurrence> records,
            final boolean showTres) {
        if (showTres) {
            return records;
        }
        List<NeiOccurrence> filtered = new ArrayList<>();
        for (NeiOccurrence record : records) {
            if (record.getContainer() != NeiOccurrence.Container.IMAGE_TRE) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private static void usage() {
        System.err.println("usage: neiinfo <file.ntf> [--json] [--no-tres] [--no-color]");
    }
}
