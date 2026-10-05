package com.vantor.nitf.nei

import org.codice.imaging.nitf.core.tre.TreEntry
import org.codice.imaging.nitf.core.tre.TreGroup
import spock.lang.Specification

/**
 * Conformance rules that need only the file itself: complexity level, TRE
 * overflow, the text segments a COMNEI dataset must carry, HISTOA length, the
 * GLAS/GFM DES subheader and the CSEXRB flags.
 *
 * Sources: JBP Table G-1 (CLEVEL), STDI-0006 Vol 4 sections 3.1.9 and 3.7 (text
 * segments), STDI-0002 Vol 1 App L (HISTOA) and Vol 2 App M (DES subheader).
 *
 * The DES subheader strings below are the literal user-defined subheaders of a
 * delivered 2026-10-02 sample, so each case is a defect actually seen.
 */
class NeiConformanceSpec extends Specification {

    static final String UUID_A = 'd2a0a2e7-3150-4503-8879-fb50da1d4c6b'
    static final String UUID_B = 'ec371951-f42a-45db-82ee-ab39663a53ab'
    static final String BLANK_UUID = ' ' * 36

    // -------------------------------------------------------------- CLEVEL

    def 'the required CLEVEL follows JBP Table G-1: #label'() {
        expect:
        NeiValidator.requiredClevel(rows, cols, bytes, 0, 0) == level

        where:
        label                                   | rows  | cols  | bytes       || level
        'ISS PAN 68464x42500, 1.17 GB'          | 68464 | 42500 | 1172621812  || 7
        'AD586D00 PAN 92398x42500, 1.9 GB'      | 92398 | 42500 | 1915066497  || 7
        'Legion PAN 30628x29847, 338 MB'        | 30628 | 29847 | 338462939   || 6
        'ISS MSI 17119x10651, 245 MB'           | 17119 | 10651 | 244833240   || 6
        'Legion MSI 7911x7458, 74 MB'           | 7911  | 7458  | 73962606    || 5
        'chip 512x512, 164 KB'                  | 512   | 512   | 164413      || 3
        '2048 is still CLEVEL 3'                | 2048  | 2048  | 1000        || 3
        '2049 needs CLEVEL 5'                   | 2049  | 100   | 1000        || 5
        '8192 is CLEVEL 5, 8193 needs 6'        | 8193  | 100   | 1000        || 6
        '65536 is CLEVEL 6, 65537 needs 7'      | 65537 | 100   | 1000        || 7
        'small image, 1.5 GB file'              | 5000  | 5000  | 1500000000  || 6
        'file of 10 GB or more'                 | 5000  | 5000  | 11000000000 || 9
    }

    def 'the image location offset counts toward the CCS extent'() {
        expect:
        NeiValidator.requiredClevel(8000, 8000, 1000, 1000, 0) == 6
        NeiValidator.requiredClevel(8000, 8000, 1000, 0, 0) == 5
    }

    def 'a CLEVEL below the requirement is an ERROR that names both values'() {
        when:
        def findings = NeiValidator.clevelFindings('FILE', clevel, bytes, 92398, 42500, 0, 0)

        then:
        findings.collect { "${it.severity}:${it.code}" as String } == expected

        where:
        clevel | bytes      || expected
        6      | 1915066497 || ['ERROR:HDR-CLEVEL-LOW']
        5      | 1915066497 || ['ERROR:HDR-CLEVEL-LOW']
        7      | 1915066497 || []
        9      | 1915066497 || []     // higher than needed is not a defect
    }

    def 'the CLEVEL finding says what was found and what was expected'() {
        when:
        def f = NeiValidator.clevelFindings('FILE', 6, 1915066497, 92398, 42500, 0, 0).first()

        then:
        f.actual.contains('06')
        f.expected.contains('07')
    }

    // -------------------------------------------------------------- IXSOFL

    def 'IXSOFL must be zero or name a TRE_OVERFLOW DES: #label'() {
        expect:
        NeiValidator.ixsoflFindings('IMAGE 1', ixsofl, desIds).collect { it.code } == expected

        where:
        label                            | ixsofl | desIds                                  || expected
        'no overflow'                    | 0      | ['CSEPHB', 'CSATTB']                    || []
        'the delivered defect'           | 1      | ['CSEPHB', 'CSEPHB', 'CSATTB']          || ['IMG-IXSOFL-NO-OVERFLOW-DES']
        'overflow DES is DES 2'          | 2      | ['CSEPHB', 'TRE_OVERFLOW']              || []
        'points at the wrong DES'        | 1      | ['CSEPHB', 'TRE_OVERFLOW']              || ['IMG-IXSOFL-NO-OVERFLOW-DES']
        'points past the last DES'       | 9      | ['CSEPHB']                              || ['IMG-IXSOFL-NO-OVERFLOW-DES']
        'points with no DES at all'      | 1      | []                                      || ['IMG-IXSOFL-NO-OVERFLOW-DES']
    }

    // -------------------------------------------------------- text segments

    def 'a COMNEI dataset needs a LICENSE and a CITATON text segment: #label'() {
        expect:
        NeiValidator.textSegmentFindings(ids).collect { "${it.severity}:${it.code}" as String }.sort() == expected.sort()

        where:
        label                       | ids                               || expected
        'none, as delivered'        | []                                || ['ERROR:TEXT-LICENSE-MISSING', 'ERROR:TEXT-CITATON-MISSING']
        'both present'              | ['LICENSE', 'CITATON']            || []
        'with the optional README'  | ['LICENSE', 'CITATON', 'README']  || []
        'license only'              | ['LICENSE']                       || ['ERROR:TEXT-CITATON-MISSING']
        'citation only'             | ['CITATON']                       || ['ERROR:TEXT-LICENSE-MISSING']
        'README is not a licence'   | ['README']                        || ['ERROR:TEXT-LICENSE-MISSING', 'ERROR:TEXT-CITATON-MISSING']
        'padded identifiers'        | ['LICENSE ', ' CITATON']          || []
    }

    // --------------------------------------------------------------- HISTOA

    def 'HISTOA must be at least 115 bytes: #label'() {
        expect:
        NeiValidator.histoaFindings('IMAGE 1', length).collect { "${it.severity}:${it.code}" as String } == expected

        where:
        label                    | length || expected
        'the delivered 41 bytes' | 41     || ['ERROR:HISTOA-TOO-SHORT']
        'one byte short'         | 114    || ['ERROR:HISTOA-TOO-SHORT']
        'the minimum'            | 115    || []
        'longer'                 | 400    || []
        'absent'                 | null   || ['ERROR:HISTOA-MISSING']
    }

    static TreEntry simple(String value) {
        [getFieldValue: { value }, isSimpleField: { true }, hasGroups: { false },
         getGroups: { [] }] as TreEntry
    }

    static TreEntry repeated(List<List<TreEntry>> repetitions) {
        [getFieldValue: { null }, isSimpleField: { false }, hasGroups: { true },
         getGroups: { repetitions.collect { rep -> [getEntries: { rep }] as TreGroup } }] as TreEntry
    }

    def 'the length of a decoded TRE is the sum of its fixed-width fields'() {
        expect: 'the delivered HISTOA: SYSTYPE 20, PC 12, PE 4, REMAP 1, LUTID 2, NEVENTS 2'
        NeiValidator.decodedLength([
                simple(' ' * 20), simple(' ' * 12), simple(' ' * 4), simple('0'),
                simple('00'), simple('00')]) == 41

        and: 'a repeated group counts every field in every repetition'
        NeiValidator.decodedLength([
                simple('00'),
                repeated([[simple('abc'), simple('de')], [simple('abc'), simple('de')]])]) == 12

        and: 'a missing value counts as nothing'
        NeiValidator.decodedLength([simple(null), simple('xy')]) == 2
    }

    // ------------------------------------------------------------ DES subheader

    def 'GLAS DES subheaders: #label'() {
        expect:
        NeiValidator.desSubheaderFindings('DES 1 (CSEPHB)', 'CSEPHB', shf).collect { "${it.severity}:${it.code}" as String } == expected

        where:
        label                                      | shf                                                                  || expected
        'CSEPHB as delivered (blank assoc UUID)'   | UUID_A + 'ALL' + '001' + BLANK_UUID + '0000'                         || ['ERROR:DES-ASSOC-UUID-BLANK']
        'CSATTB as delivered (blank NUMAIS)'       | UUID_A + '   ' + '000' + '0000'                                      || ['ERROR:DES-NUMAIS-INVALID']
        'CSSFAB as delivered (000, blank length)'  | UUID_A + '000' + '000' + '    '                                      || ['ERROR:DES-NUMAIS-INVALID', 'ERROR:DES-RESERVEDSUBH-LEN']
        'a conformant DES, ALL'                    | UUID_A + 'ALL' + '001' + UUID_B + '0000'                             || []
        'a conformant DES, no associations'        | UUID_A + 'ALL' + '000' + '0000'                                      || []
        'numeric NUMAIS with its display levels'   | UUID_A + '002' + '001' + '002' + '000' + '0000'                      || []
        'NUMAIS 998 is the top of the range'       | UUID_A + 'ALL' + '000' + '0000'                                      || []
        'one of two assoc UUIDs blank'             | UUID_A + 'ALL' + '002' + UUID_B + BLANK_UUID + '0000'                || ['ERROR:DES-ASSOC-UUID-BLANK']
        'reserved length not 0000'                 | UUID_A + 'ALL' + '000' + '0012'                                      || ['ERROR:DES-RESERVEDSUBH-LEN']
        'too short to parse'                       | 'abc'                                                                || ['ERROR:DES-SUBHEADER-MALFORMED']
        'declares more UUIDs than it carries'      | UUID_A + 'ALL' + '003' + UUID_B + '0000'                             || ['ERROR:DES-SUBHEADER-MALFORMED']
    }

    def 'a blank assoc UUID finding names which one'() {
        when:
        def f = NeiValidator.desSubheaderFindings('DES 1 (CSEPHB)', 'CSEPHB',
                UUID_A + 'ALL' + '002' + UUID_B + BLANK_UUID + '0000').first()

        then:
        f.field == 'ASSOC_ELEM_UUID2'
    }

    // ------------------------------------------------------------ CSEXRB flags

    def 'CSEXRB flags must be 0 or 1: #label'() {
        expect:
        NeiValidator.csexrbFlagFindings('IMAGE 1', 'ATM_REFR_FLAG', value).collect { "${it.severity}:${it.code}" as String } == expected

        where:
        label           | value  || expected
        'zero'          | '0'    || []
        'one'           | '1'    || []
        'blank'         | ''     || ['ERROR:CSEXRB-FLAG-BLANK']
        'spaces'        | ' '    || ['ERROR:CSEXRB-FLAG-BLANK']
        'null'          | null   || ['ERROR:CSEXRB-FLAG-BLANK']
        'out of range'  | '2'    || ['ERROR:CSEXRB-FLAG-INVALID']
        'letter'        | 'Y'    || ['ERROR:CSEXRB-FLAG-INVALID']
    }
}
