package com.vantor.nitf.nei;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Command line front end for {@link NeiValidator}.
 *
 * <p>Exit status: 0 when no ERROR finding was raised, 1 when one was, 2 on a
 * usage problem, 3 when a file could not be read. WARN and INFO never fail a
 * run, so this is usable in a build without pinning every legal-but-odd field.
 */
public final class NeiValidate {
    private NeiValidate() {
    }

    public static void main(final String[] args) {
        List<String> paths = new ArrayList<>();
        boolean json = false;
        boolean errorsOnly = false;
        boolean quiet = false;
        for (String argument : args) {
            switch (argument) {
                case "--json":
                    json = true;
                    break;
                case "--errors-only":
                    errorsOnly = true;
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "--no-color":
                    // Output is already uncoloured; accepted for command compatibility.
                    break;
                case "-h":
                case "--help":
                    usage();
                    return;
                default:
                    if (argument.startsWith("--")) {
                        System.err.println("neivalidator: unexpected argument: " + argument);
                        usage();
                        System.exit(2);
                    }
                    paths.add(argument);
            }
        }
        if (paths.isEmpty()) {
            usage();
            System.exit(2);
        }

        int exit = 0;
        List<String> jsonFiles = new ArrayList<>();
        for (String path : paths) {
            File file = new File(path);
            if (!file.isFile()) {
                System.err.println("neivalidator: cannot open '" + path + "'");
                exit = Math.max(exit, 3);
                continue;
            }
            NeiValidationReport report;
            try {
                report = new NeiValidator().validate(file);
            } catch (Exception e) {
                System.err.println("neivalidator: " + file + ": " + e.getMessage());
                exit = Math.max(exit, 3);
                continue;
            }
            if (report.hasErrors()) {
                exit = Math.max(exit, 1);
            }
            if (json) {
                jsonFiles.add(toJson(file, report, errorsOnly));
            } else {
                printText(file, report, errorsOnly, quiet);
            }
        }
        if (json) {
            System.out.println("{\"files\":[" + String.join(",", jsonFiles) + "]}");
        }
        System.exit(exit);
    }

    private static void printText(final File file, final NeiValidationReport report,
            final boolean errorsOnly, final boolean quiet) {
        int errors = report.count(NeiFinding.Severity.ERROR);
        int warnings = report.count(NeiFinding.Severity.WARN);
        System.out.printf("%s: %d error(s), %d warning(s)%n", file, errors, warnings);
        if (!quiet) {
            for (NeiFinding finding : report.getFindings()) {
                if (errorsOnly && finding.getSeverity() != NeiFinding.Severity.ERROR) {
                    continue;
                }
                if (finding.getSeverity() == NeiFinding.Severity.INFO && errorsOnly) {
                    continue;
                }
                System.out.printf("  %-5s %-28s %-16s %s%n",
                        finding.getSeverity(), finding.getCode(), finding.getLocation(),
                        detail(finding));
            }
        }
        if (errors + warnings > 0) {
            System.out.println("  --");
            for (Map.Entry<String, Integer> entry : report.byCode().entrySet()) {
                System.out.printf("  %4d  %s%n", entry.getValue(), entry.getKey());
            }
        }
    }

    private static String detail(final NeiFinding finding) {
        StringBuilder out = new StringBuilder();
        if (!finding.getField().isEmpty()) {
            out.append(finding.getField()).append(' ');
        }
        if (!finding.getActual().isEmpty() || !finding.getExpected().isEmpty()) {
            out.append("found ").append(NeiFinding.quote(finding.getActual()));
            if (!finding.getExpected().isEmpty()) {
                out.append(", expected ").append(finding.getExpected());
            }
        }
        if (!finding.getMessage().isEmpty()) {
            if (out.length() > 0) {
                out.append(" -- ");
            }
            out.append(finding.getMessage());
        }
        return out.toString();
    }

    private static String toJson(final File file, final NeiValidationReport report,
            final boolean errorsOnly) {
        StringBuilder out = new StringBuilder();
        out.append("{\"file\":").append(quote(file.getPath()));
        out.append(",\"errors\":").append(report.count(NeiFinding.Severity.ERROR));
        out.append(",\"warnings\":").append(report.count(NeiFinding.Severity.WARN));
        out.append(",\"findings\":[");
        boolean first = true;
        for (NeiFinding finding : report.getFindings()) {
            if (errorsOnly && finding.getSeverity() != NeiFinding.Severity.ERROR) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append("{\"severity\":").append(quote(finding.getSeverity().name()))
               .append(",\"code\":").append(quote(finding.getCode()))
               .append(",\"location\":").append(quote(finding.getLocation()))
               .append(",\"field\":").append(quote(finding.getField()))
               .append(",\"actual\":").append(quote(finding.getActual()))
               .append(",\"expected\":").append(quote(finding.getExpected()))
               .append(",\"message\":").append(quote(finding.getMessage()))
               .append('}');
        }
        out.append("]}");
        return out.toString();
    }

    static String quote(final String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    private static void usage() {
        System.err.println("usage: neivalidator <file.ntf>... "
                + "[--json] [--errors-only] [--quiet] [--no-color]");
        System.err.println();
        System.err.println("Reports conformance and internal-consistency defects using only the");
        System.err.println("NITF itself: no level1a XML, no focused sidecar, no reconstruction.");
        System.err.println("Exit 0 = no errors, 1 = errors found, 2 = usage, 3 = unreadable.");
    }
}
