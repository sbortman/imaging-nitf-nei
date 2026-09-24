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

    def 'IREPBAND values are classed against the standard set'() {
        expect:
        NeiValidator.irepbandClass(value) == klass

        where:
        value  || klass
        ''     || 'standard'   // all BCS spaces, the default
        '  '   || 'standard'
        'M '   || 'standard'   // left-justified, space-filled to 2
        'R'    || 'standard'
        'G'    || 'standard'
        'B'    || 'standard'
        'LU'   || 'standard'
        'Y'    || 'standard'
        'Cb'   || 'standard'
        'Cr'   || 'standard'
        'LX'   || 'standard'   // location grids (GEOSDE)
        'LY'   || 'standard'
        'N'    || 'profile'    // near-IR: a producer profile extension, not the base set
        '00'   || 'invalid'    // a band index is not a representation
        '03'   || 'invalid'
        'r'    || 'invalid'    // the field is case-sensitive: Cb and Cr are mixed case
        'CB'   || 'invalid'
        null   || 'standard'
    }

    def 'band representation findings: #label'() {
        when:
        def findings = NeiValidator.bandRepresentationFindings('IMAGE 1', irep, reps)

        then:
        findings.collect { "${it.severity}:${it.code}" as String }.sort() == expected.sort()

        where:
        label                              | irep    | reps                     || expected
        'band index written as IREPBAND'   | 'MULTI' | ['00', '01', '02', '03'] || ['ERROR:IMG-IREPBAND-INVALID'] * 4 + ['WARN:IMG-MULTI-NO-RGB']
        'B,G,R plus a profile N'           | 'MULTI' | ['B', 'G', 'R', 'N']     || ['INFO:IMG-IREPBAND-PROFILE']
        'B,G,R with NIR left blank'        | 'MULTI' | ['B', 'G', 'R', '']      || []
        'multiband naming no display band' | 'MULTI' | ['', '', '', '']         || ['WARN:IMG-MULTI-NO-RGB']
        'fewer than three bands'           | 'MULTI' | ['', '']                 || []
        'mono, correct'                    | 'MONO'  | ['M']                    || []
        'mono, band index'                 | 'MONO'  | ['00']                   || ['ERROR:IMG-IREPBAND-INVALID']
        'mono, blank'                      | 'MONO'  | ['']                     || ['WARN:IMG-IREPBAND-MONO']
        'true colour'                      | 'RGB'   | ['R', 'G', 'B']          || []
    }

    def 'an invalid IREPBAND names the band and says why it matters'() {
        when:
        def f = NeiValidator.bandRepresentationFindings('IMAGE 1', 'MULTI', ['B', '01', 'R'])
                .find { it.code == 'IMG-IREPBAND-INVALID' }

        then:
        f.field == 'IREPBAND002'
        f.actual == '01'
        f.toString().contains('R, G, B')
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

    // ---------------------------------------------------------- frame rules

    private static NeiRecord rec(String type, String field, String value) {
        new NeiRecord(type, [(field): value], 0, 0)
    }

    private static NeiValidationReport frameReport(List<List> segments) {
        // segments: [type, frameValue, desVersion]
        def records = new LinkedHashMap<NeiRecord, String>()
        def versions = new LinkedHashMap<NeiRecord, Integer>()
        segments.eachWithIndex { seg, i ->
            String field = seg[0] == 'CSATTB' ? 'ECI_ECF_ATT' : 'ECI_ECF_EPHEM'
            def r = rec(seg[0] as String, field, seg[1] as String)
            records[r] = "DES ${i + 1} (${seg[0]})".toString()
            versions[r] = seg[2] as Integer
        }
        def report = new NeiValidationReport()
        new NeiValidator().checkReferenceFrameAgreement(report, records, versions)
        report
    }

    private static List codes(NeiValidationReport report) {
        report.findings.collect { it.code }
    }

    def 'attitude and ephemeris in different frames is an error'() {
        // The real shape of the regression: ossim lost CSATTB's ECI_ECF_ATT
        // default, so attitude said ECI while ephemeris still said ECF. Both
        // values are legal on their own, which is why no per-field check saw it.
        given:
        def report = frameReport([['CSEPHB', '1', 1], ['CSEPHB', '1', 1], ['CSATTB', '0', 1]])

        expect:
        'NEI-FRAME-DISAGREE' in codes(report)
        report.hasErrors()

        and: 'the finding names which segment said what, not just that they differ'
        def f = report.findings.find { it.code == 'NEI-FRAME-DISAGREE' }
        f.actual.contains('DES 3 (CSATTB) ECI_ECF_ATT=0 (ECI)')
        f.actual.contains('ECI_ECF_EPHEM=1 (ECF)')
    }

    def 'agreement is reported as context, never as a defect'() {
        given:
        def report = frameReport([['CSEPHB', '1', 1], ['CSATTB', '1', 1]])

        expect:
        codes(report) == ['NEI-FRAME']
        !report.hasErrors()
    }

    def 'ECI at DESVER 01 is unusable even when every segment agrees'() {
        // App M: version 1 carries no ECI-to-ECF parameters, so a consumer
        // cannot mensurate. Self-consistent, still broken -- the disagreement
        // rule alone would pass this.
        given:
        def report = frameReport([['CSEPHB', '0', 1], ['CSATTB', '0', 1]])

        expect:
        codes(report).count { it == 'NEI-FRAME-ECI-UNUSABLE' } == 2
        'NEI-FRAME-DISAGREE' !in codes(report)
        report.hasErrors()
    }

    def 'ECI at DESVER 02 is allowed -- the transform parameters are present'() {
        given:
        def report = frameReport([['CSEPHB', '0', 2], ['CSATTB', '0', 2]])

        expect:
        codes(report) == ['NEI-FRAME']
        !report.hasErrors()
    }

    def 'a blank frame flag is skipped rather than guessed at'() {
        given:
        def report = frameReport([['CSEPHB', '1', 1], ['CSATTB', '   ', 1]])

        expect: 'the blank segment contributes nothing, so the rest still agree'
        codes(report) == ['NEI-FRAME']
        !report.hasErrors()
    }

}
