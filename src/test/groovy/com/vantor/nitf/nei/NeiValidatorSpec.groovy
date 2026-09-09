package com.vantor.nitf.nei

import spock.lang.Specification

/**
 * Unit coverage for the validator's decision helpers and its report.
 *
 * Rule coverage that needs a real file lives in NeiValidatorSampleSpec, which
 * skips when the build was not pointed at a sample directory.
 */
class NeiValidatorSpec extends Specification {

    def 'placeholder detection treats hyphen and zero fills as unset'() {
        expect:
        NeiValidator.isPlaceholder(value) == unset

        where:
        value            || unset
        ''               || true
        '--------------' || true
        '   '            || true
        '00000000000000' || true
        '-0- 0-'         || true
        '20240607235000' || false
        '2024-06-07'     || false
        'X'              || false
    }

    def 'leadingInt reads a fixed-width count out of a raw TRE payload'() {
        expect:
        NeiValidator.leadingInt(payload?.getBytes('US-ASCII'), width) == expected

        where:
        payload      | width || expected
        '00004REST'  | 5     || 4
        '00003'      | 5     || 3
        '00000REST'  | 5     || 0
        '  12 REST'  | 5     || 12
        'ABCDE'      | 5     || null      // not a number
        '004'        | 5     || null      // shorter than the field
        null         | 5     || null
    }

    def 'numeric parsing rejects blanks and junk rather than throwing'() {
        expect:
        NeiValidator.parseInt(text) == asInt
        NeiValidator.parseDouble(text) == asDouble

        where:
        text     || asInt | asDouble
        '42'     || 42    | 42.0d
        ' 42 '   || 42    | 42.0d
        ''       || null  | null
        '   '    || null  | null
        null     || null  | null
        'abc'    || null  | null
        '0.02'   || null  | 0.02d
    }

    def 'a report counts by severity and only errors fail a run'() {
        given:
        def report = new NeiValidationReport()

        when:
        report.add(NeiFinding.error('E-ONE', 'FILE', 'F', 'a', 'b', 'm'))
        report.add(NeiFinding.error('E-ONE', 'IMAGE 1', 'F', 'a', 'b', 'm'))
        report.add(NeiFinding.warn('W-ONE', 'FILE', 'F', 'a', 'b', 'm'))
        report.add(NeiFinding.info('I-ONE', 'FILE', 'F', 'a', 'm'))

        then:
        report.findings.size() == 4
        report.count(NeiFinding.Severity.ERROR) == 2
        report.count(NeiFinding.Severity.WARN) == 1
        report.count(NeiFinding.Severity.INFO) == 1
        report.hasErrors()
        report.byCode() == ['E-ONE': 2, 'W-ONE': 1, 'I-ONE': 1]

        and: 'the summary can be narrowed to one severity, as --errors-only needs'
        report.byCode(NeiFinding.Severity.ERROR) == ['E-ONE': 2]
        report.byCode(NeiFinding.Severity.INFO) == ['I-ONE': 1]
    }

    def 'a report with no errors does not fail a run'() {
        given:
        def report = new NeiValidationReport()

        when:
        report.add(NeiFinding.warn('W', 'FILE', 'F', 'a', 'b', 'm'))
        report.add(NeiFinding.info('I', 'FILE', 'F', 'a', 'm'))

        then:
        !report.hasErrors()
        report.count(NeiFinding.Severity.ERROR) == 0
    }

    def 'an empty value is reported as empty rather than as an empty string'() {
        expect:
        NeiFinding.quote('') == '(empty)'
        NeiFinding.quote('U') == "'U'"
    }

    def 'a finding states both what was found and what was expected'() {
        given:
        def finding = NeiFinding.error('CSEXRB-NUM-LINES', 'IMAGE 1', 'CSEXRB.NUM_LINES',
                '0', 'NROWS 640', 'the TRE and the image disagree')

        expect:
        finding.toString().contains('CSEXRB-NUM-LINES')
        finding.toString().contains("found '0'")
        finding.toString().contains('expected NROWS 640')
        finding.severity == NeiFinding.Severity.ERROR
    }

    def 'info findings carry no expectation'() {
        expect:
        NeiFinding.info('X', 'FILE', 'F', 'a', 'm').expected.isEmpty()
    }

    def 'json output escapes what would otherwise break the document'() {
        expect:
        NeiValidate.quote(raw) == quoted

        where:
        raw            || quoted
        'plain'        || '"plain"'
        'say "hi"'     || '"say \\"hi\\""'
        'back\\slash'  || '"back\\\\slash"'
        'line\nbreak'  || '"line\\nbreak"'
        'tab\there'    || '"tab\\there"'
        '\u0001'      || '"\\u0001"'   // control chars are escaped, never emitted raw
        ''            || '""'
    }
}
