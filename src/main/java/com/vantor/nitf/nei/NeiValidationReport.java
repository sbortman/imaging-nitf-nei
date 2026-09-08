package com.vantor.nitf.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Findings from one validation run, in discovery order. */
public final class NeiValidationReport {
    private final List<NeiFinding> findings = new ArrayList<>();

    void add(final NeiFinding finding) {
        findings.add(finding);
    }

    public List<NeiFinding> getFindings() {
        return Collections.unmodifiableList(findings);
    }

    public int count(final NeiFinding.Severity severity) {
        int n = 0;
        for (NeiFinding finding : findings) {
            if (finding.getSeverity() == severity) {
                n++;
            }
        }
        return n;
    }

    public boolean hasErrors() {
        return count(NeiFinding.Severity.ERROR) > 0;
    }

    /** Findings grouped by rule code, for a summary that does not repeat itself. */
    public Map<String, Integer> byCode() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NeiFinding finding : findings) {
            counts.merge(finding.getCode(), 1, Integer::sum);
        }
        return counts;
    }
}
