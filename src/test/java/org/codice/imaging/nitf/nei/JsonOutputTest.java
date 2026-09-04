package org.codice.imaging.nitf.nei;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class JsonOutputTest {
    @Test
    public void escapesFieldNamesAndValues() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("quoted\"field", "line one\nline two\\end");
        NeiRecord record = new NeiRecord("TEST", fields, 20, 20);
        NeiOccurrence occurrence = new NeiOccurrence(
                NeiOccurrence.Container.IMAGE_TRE, 1, record);

        String json = JsonOutput.write(new File("sample.ntf"),
                Collections.singletonList(occurrence), Collections.emptyList());

        assertTrue(json.contains("\"quoted\\\"field\": \"line one\\nline two\\\\end\""));
        assertTrue(json.contains("\"remainingBytes\": 0"));
        assertTrue(json.endsWith("}\n"));
    }
}

