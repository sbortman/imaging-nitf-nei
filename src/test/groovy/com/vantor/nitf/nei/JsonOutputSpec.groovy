package com.vantor.nitf.nei

import spock.lang.Specification

class JsonOutputSpec extends Specification {
    def 'escapes field names and values'() {
        given:
        def fields = ['quoted"field': 'line one\nline two\\end'] as LinkedHashMap
        def record = new NeiRecord('TEST', fields, 20, 20)
        def occurrence = new NeiOccurrence(NeiOccurrence.Container.IMAGE_TRE, 1, record)

        when:
        def json = JsonOutput.write(new File('sample.ntf'), [occurrence], [])

        then:
        json.contains('"quoted\\"field": "line one\\nline two\\\\end"')
        json.contains('"remainingBytes": 0')
        json.endsWith('}\n')
    }
}
