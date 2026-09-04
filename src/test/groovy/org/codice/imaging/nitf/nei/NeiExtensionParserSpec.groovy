package org.codice.imaging.nitf.nei

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class NeiExtensionParserSpec extends Specification {
    private final NeiExtensionParser parser = new NeiExtensionParser()

    def 'parses CSEXRB with associations'() {
        given:
        def payload = new StringBuilder()
        payload << 'eea7076f-7c64-4620-b772-ac813866a92f' << '002'
        payload << '1aa17a53-1c60-4fab-945b-5d12416ca822'
        payload << 'd4c6f40e-5597-49b7-8254-c7fcee59b328'
        payload << pad('WV', 6) << pad('03', 6) << pad('PAN', 6) << 'S'
        payload << spaces(36)
        payload << '20240607' << '85800.000000000' << '+00003.413589666'
        payload << spaces(84) << spaces(10)
        payload << '0000000' << '00000'
        payload << spaces(20) << '  ' << '99'
        payload << spaces(14) << spaces(3) << spaces(10) << spaces(3)
        payload << ' ' << '00000'

        when:
        def record = parse('CSEXRB', payload)

        then:
        record.getInt('NUM_ASSOC_DES') == 2
        record.get('PLATFORM_ID').trim() == 'WV'
        record.get('DAY_FIRST_LINE_IMAGE') == '20240607'
        record.get('ASSOC_DES[1].ASSOC_DES_UUID') ==
                'd4c6f40e-5597-49b7-8254-c7fcee59b328'
        record.remainingBytes == 0
    }

    def 'parses a CSRLSB grid'() {
        given:
        def payload = new StringBuilder('0202')
        (1..4).each { block ->
            (1..4).each { corner ->
                payload << String.format('+0000000%d.%d0', block, corner)
            }
        }

        when:
        def record = parse('CSRLSB', payload)

        then:
        record.get('ROW_BLOCK[1].COLUMN_BLOCK[1].RS_DT_4') == '+00000004.40'
        record.remainingBytes == 0
    }

    def 'parses PIXQLA associated images'() {
        given:
        def payload = new StringBuilder('00200300700021')
        payload << pad('Cloud', 40) << pad('Sensor_Saturated', 40)

        when:
        def record = parse('PIXQLA', payload)

        then:
        record.get('ASSOCIATED_IMAGE_SEGMENT[1].AISDLVL') == '007'
        record.getInt('NPIXQUAL') == 2
        record.get('PIXEL_CONDITION[1].PQ_CONDITION').trim() == 'Sensor_Saturated'
    }

    def 'parses inclusive CSWRPB polynomial orders'() {
        given:
        def payload = new StringBuilder('1F1')
        payload << '00.12345678'
        8.times { payload << '0000512' }
        payload << '1200'
        (0..2).each { j ->
            (0..1).each { i ->
                payload << String.format('+%d.00000000000000E+0%d', i, j)
            }
        }
        payload << '+9.99999999999999E+01' << '00000'

        when:
        def record = parse('CSWRPB', payload)

        then:
        record.get('WARP_DATA[0].A[2][1]') == '+1.00000000000000E+02'
        record.get('WARP_DATA[0].B[0][0]') == '+9.99999999999999E+01'
        record.remainingBytes == 0
    }

    def 'parses CSEPHB vectors and velocity'() {
        given:
        def payload = new StringBuilder('12321')
        payload << '000.02000000020240607235000.20000000000002'
        payload << '-04650413.20+01029613.77+05110465.09'
        payload << '-04651390.88+01030212.19+05109457.17'
        payload << '00000008501' << '1' << '000000073' << 'N'
        payload << '-00004888.95+00002992.17-00005039.01'
        payload << '-00004886.20+00002990.05-00005041.55'

        when:
        def record = parse('CSEPHB', payload)

        then:
        record.getInt('NUM_EPHEM') == 2
        record.get('VELOCITY[0].VEL_X') == '-00004888.95'
        record.remainingBytes == 0
    }

    def 'parses a CSATTB quaternion'() {
        given:
        def payload = '02320' + '000.020000000' + '20240607' + '235000.300000000' +
                '00001' + '-0.180248530324575' + '-0.838233121509416' +
                '-0.409883760738082' + '-0.311241070560455' + '000000000'

        when:
        def record = parser.parse('CSATTB', 1, payload.getBytes(StandardCharsets.ISO_8859_1))

        then:
        record.getInt('NUM_ATT') == 1
        record.get('ATTITUDE[0].Q4') == '-0.311241070560455'
        record.remainingBytes == 0
    }

    def 'parses a CSSFAB line scanner'() {
        given:
        def payload = new StringBuilder('S 00.0000000000000')
        payload << '0011' << '20240607'
        payload << '85800.000000000' << '16.00000000'
        payload << '+00.000000+00.000000+00.000000'
        payload << '+0.0000000+0.0000000+0.0000000'
        payload << '+00000.00000' << '00000.00582' << '001'
        payload << '+00.0000000+00.0000000+00.0058240+00.0005800'
        payload << '000000000'

        when:
        def record = parse('CSSFAB', payload)

        then:
        record.getInt('NUM_FA_PAIRS') == 1
        record.get('FIELD_ALIGNMENT[0].END_FALIGN_X') == '+00.0058240'
        record.remainingBytes == 0
    }

    def 'retains the Maxar CSSFAB preamble'() {
        given:
        def payload = new StringBuilder(spaces(173))
        payload << 'S 00.00000000000000011        00000.00000000000.00000000000.000000000' +
                '.00000000.000000000.000000000.0000000-1.5707963000000.0000000000' +
                '.00000000000000000'

        when:
        def record = parse('CSSFAB', payload)

        then:
        record.get('VENDOR_PREAMBLE').length() == 173
        record.get('ANGOFF_Z') == '-1.5707963'
        record.remainingBytes == 0
    }

    def 'parses CSCSDB formula and correlation branches'() {
        given:
        def payload = new StringBuilder('20240501112')
        payload << '1'
        payload << '20000101' << '000000.000000000' << '0'
        payload << '1' << '0' << '0' << '1' << '00'
        payload << '1' << '00000000' << '00000.000000000'
        payload << '000.000000000' << '000' << '1'
        payload << '1' << '0' << '0' << '1' << '00' << '0'
        payload << '0' << '0'
        payload << '1' << '000' << '00' << '01' << '02'
        payload << '1' << '01' << '00' << '01'
        payload << '0' << '1.000' << '1.000000' << '0.100000'
        payload << '10.000000' << '0.000000000000000E+00'
        payload << '0' << '000000000'

        when:
        def record = parse('CSCSDB', payload)

        then:
        record.getInt('CORE_SETS') == 1
        record.get('CORE_SET[0].GROUP[0].POST_SR_SPDCF') == '00'
        record.get('SPDCF[0].SPDCF_CONSTITUENT[0].FP_BETA') == '10.000000'
        record.remainingBytes == 0
    }

    def 'reports bounds failures with the field and offset'() {
        when:
        parser.parse('PIXQLA', 'ALL0001'.getBytes(StandardCharsets.ISO_8859_1))

        then:
        def failure = thrown(NeiFormatException)
        failure.message.contains('PQ_BIT_VALUE')
        failure.message.contains('offset 7')
    }

    private NeiRecord parse(String type, CharSequence payload) {
        parser.parse(type, payload.toString().getBytes(StandardCharsets.ISO_8859_1))
    }

    private static String pad(String value, int width) {
        String.format("%-${width}s", value)
    }

    private static String spaces(int count) {
        pad('', count)
    }
}
