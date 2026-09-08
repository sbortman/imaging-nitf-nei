package com.vantor.nitf.nei

import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

/**
 * Regression tests against real vendor products, read through the stock
 * imaging-nitf jars exactly as an application would.
 *
 * The samples live outside this repository. Point the build at them with
 * {@code -PneiSamplesDir=/path/to/nei-samples} or {@code NEI_SAMPLES_DIR};
 * with neither set every feature here skips rather than fails.
 *
 * The first run beside a sample writes a golden summary next to it
 * ({@code product.ntf} produces {@code product.ntf.nei.txt}); later runs
 * compare against it, so a change in what the parser decodes shows up as a
 * diff. Rewrite the goldens deliberately with {@code -PneiRegolden}.
 */
@Requires({ NeiSamples.available() })
class NeiSampleSpec extends Specification {

    @Shared
    List<File> samples = NeiSamples.files()

    def 'scans #sample.name without a parse failure'() {
        when:
        NeiScanReport report = new NeiNitfAdapter().scanReport(sample)

        then:
        report.failures.collect {
            "${it.container} ${it.segmentIndex} ${it.type}: ${it.message}"
        } == []

        and: 'a product with no NEI content at all would mean the seam has closed'
        !report.records.isEmpty()

        where:
        sample << samples
    }

    def 'consumes every payload byte in #sample.name'() {
        given:
        NeiScanReport report = new NeiNitfAdapter().scanReport(sample)

        expect:
        report.records.findAll { it.record.remainingBytes != 0 }.collect {
            "${it.record.type} left ${it.record.remainingBytes} of ${it.record.payloadLength} bytes"
        } == []

        where:
        sample << samples
    }

    def 'decodes #sample.name to the same fields as the golden summary'() {
        given:
        File golden = NeiSamples.golden(sample)
        String actual = NeiSamples.summarize(new NeiNitfAdapter().scanReport(sample))

        and: 'a missing golden is a new baseline, not a failure'
        if (!golden.isFile() || NeiSamples.regolden()) {
            golden.setText(actual, 'UTF-8')
            println "NeiSampleSpec: wrote baseline ${golden.name}"
        }

        expect:
        actual == golden.getText('UTF-8')

        where:
        sample << samples
    }
}
