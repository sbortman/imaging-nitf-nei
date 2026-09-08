package com.vantor.nitf.nei

/**
 * Locates the real vendor products used by {@link NeiSampleSpec}.
 *
 * The samples are commercial imagery and are deliberately not committed to this
 * repository. The build passes their location through as a system property; see
 * the {@code test} task in build.gradle.
 */
class NeiSamples {
    private static final String DIRECTORY_PROPERTY = 'nei.samples.dir'
    private static final String DIRECTORY_VARIABLE = 'NEI_SAMPLES_DIR'
    private static final String REGOLDEN_PROPERTY = 'nei.regolden'
    private static final List<String> EXTENSIONS = ['.ntf', '.nitf']

    /** The configured sample directory, or null when the build was not pointed at one. */
    static File directory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY) ?: System.getenv(DIRECTORY_VARIABLE)
        configured ? new File(configured) : null
    }

    /** True when a sample directory was configured and actually holds NITF files. */
    static boolean available() {
        !files().isEmpty()
    }

    /** True when goldens should be rewritten rather than compared. */
    static boolean regolden() {
        Boolean.getBoolean(REGOLDEN_PROPERTY)
    }

    /** Every NITF file in the sample directory, in a stable order. */
    static List<File> files() {
        File dir = directory()
        if (dir == null || !dir.isDirectory()) {
            return []
        }
        (dir.listFiles() ?: new File[0])
                .findAll { File candidate ->
                    candidate.isFile() && EXTENSIONS.any { String extension ->
                        candidate.name.toLowerCase(Locale.ROOT).endsWith(extension)
                    }
                }
                .sort { File candidate -> candidate.name }
    }

    /** The golden summary that belongs beside a given sample. */
    static File golden(File sample) {
        new File(sample.parentFile, sample.name + '.nei.txt')
    }

    /**
     * Renders a scan as stable, diffable text.
     *
     * Nothing environment-specific goes in - no absolute paths, no timestamps -
     * so a change in this file's output is a change in what the parser decoded.
     */
    static String summarize(NeiScanReport report) {
        StringBuilder text = new StringBuilder(8192)
        report.records.each { NeiOccurrence occurrence ->
            NeiRecord record = occurrence.record
            text << "${occurrence.container} ${occurrence.segmentIndex} ${record.type}"
            text << " payload=${record.payloadLength}"
            text << " consumed=${record.bytesConsumed}"
            text << " remaining=${record.remainingBytes}\n"
            record.fields.each { String name, String value ->
                text << "  ${name}=${value.trim()}\n"
            }
        }
        report.failures.each { NeiParseFailure failure ->
            text << "FAILED ${failure.container} ${failure.segmentIndex} "
            text << "${failure.type}: ${failure.message}\n"
        }
        text.toString()
    }
}
