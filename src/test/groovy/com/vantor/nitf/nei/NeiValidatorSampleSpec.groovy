package com.vantor.nitf.nei

import spock.lang.Requires
import spock.lang.Specification

/**
 * Runs the validator over real vendor products.
 *
 * These assert invariants of the REPORT rather than a fixed set of findings:
 * the sample directory is whatever the operator points at, so a spec that
 * pinned exact defect counts would fail on a clean product and pass vacuously
 * on an empty directory. What must always hold is that the validator completes,
 * and that every finding it emits is actionable -- a code, a location, and for
 * anything it calls a defect, both an observed and an expected value.
 */
@Requires({ NeiSamples.available() })
class NeiValidatorSampleSpec extends Specification {

    def 'validates every sample without throwing'() {
        expect:
        new NeiValidator().validate(sample) != null

        where:
        sample << NeiSamples.files()
    }

    def 'every finding is actionable'() {
        given:
        def report = new NeiValidator().validate(sample)

        expect:
        report.findings.every { NeiFinding finding ->
            !finding.code.isEmpty() && !finding.location.isEmpty()
        }

        and: 'a defect always says what it expected, so it can be argued with'
        report.findings
                .findAll { it.severity != NeiFinding.Severity.INFO }
                .every { !it.expected.isEmpty() }

        where:
        sample << NeiSamples.files()
    }

    def 'severity counts agree with the findings list'() {
        given:
        def report = new NeiValidator().validate(sample)

        expect:
        report.count(NeiFinding.Severity.ERROR)
                + report.count(NeiFinding.Severity.WARN)
                + report.count(NeiFinding.Severity.INFO) == report.findings.size()

        and:
        report.hasErrors() == (report.count(NeiFinding.Severity.ERROR) > 0)

        where:
        sample << NeiSamples.files()
    }
}
