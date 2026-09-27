package com.vantor.nitf.nei

import spock.lang.Requires
import spock.lang.Specification

class NitfMetadataSpec extends Specification {

    static File sample(String relative) {
        String root = System.getProperty('ossim.data')
        root ? new File(root, relative) : null
    }

    static final String BSG_RGB = 'blacksky-test/BSG-120-20221206-112520-50471225_georeferenced.ntf'
    static final String BSG_PAN = 'blacksky-test/BSG-120-20221206-112520-50471225_georeferenced-pan.ntf'

    static boolean has(String relative) {
        sample(relative)?.isFile()
    }

    @Requires(value = { has(BSG_RGB) }, reason = 'needs $OSSIM_DATA/blacksky-test samples')
    def "reads header fields and decoded TREs of a BlackSky RGB product"() {
        when:
        NitfMetadata metadata = NitfMetadata.read(sample(BSG_RGB))
        Map<String, String> image0 = metadata.imageSegments()[0]

        then:
        metadata.fileFields().FSCLAS == 'U'
        metadata.imageSegments().size() == 2
        image0.IREP == 'RGB'
        image0.NBANDS == '3'
        image0.ISCLAS == 'U'
        image0.IDATIM == '20221206112520'
        image0['STDIDC.MISSION'] == 'GL20NA'
        image0['PIAIMC.SENSNAME'] == 'GL20'
        image0['CSEXRA.SENSOR'] == 'MS'
        image0.NROWS && image0.NCOLS
        image0.IGEOLO.split(' ').size() == 4
        metadata.imageSegments()[1].IREP == 'NODISPLY'
        metadata.flat()['image0.STDIDC.MISSION'] == 'GL20NA'
        metadata.flat().FSCLAS == 'U'
    }

    @Requires(value = { has(BSG_PAN) }, reason = 'needs $OSSIM_DATA/blacksky-test samples')
    def "a panchromatic product is MONO with one band"() {
        when:
        Map<String, String> image0 = NitfMetadata.read(sample(BSG_PAN)).imageSegments()[0]

        then:
        image0.IREP == 'MONO'
        image0.NBANDS == '1'
        image0['CSEXRA.SENSOR'] == 'PAN'
    }

    @Requires(value = { has(BSG_RGB) }, reason = 'needs $OSSIM_DATA/blacksky-test samples')
    def "blank values are omitted and the maps are immutable"() {
        given:
        Map<String, String> fileFields = NitfMetadata.read(sample(BSG_RGB)).fileFields()

        expect:
        fileFields.values().every { it != null && !it.trim().isEmpty() }

        when:
        fileFields.put('X', 'y')

        then:
        thrown(UnsupportedOperationException)
    }

    def "a file that is not a NITF fails cleanly with an exception, not a crash"() {
        given:
        File junk = File.createTempFile('not-a-nitf', '.ntf')
        junk.deleteOnExit()
        junk.text = 'NITF02.10' + ('x' * 400)

        when:
        NitfMetadata.read(junk)

        then:
        Exception e = thrown()
        e instanceof org.codice.imaging.nitf.core.common.NitfFormatException || e instanceof NeiFormatException ||
                e instanceof IllegalStateException || e instanceof IllegalArgumentException
    }
}
