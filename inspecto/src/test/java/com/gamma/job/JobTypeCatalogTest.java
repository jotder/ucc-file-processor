package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the type catalog (§4.3, S1) catches a Job Type emitting a signal type it never declared —
 *  the class of bug that let {@code report} declare {@code emits=[]} while firing {@code REPORT_READY}. */
class JobTypeCatalogTest {

    @Test
    void flagsAnUncataloguedEmission() {
        JobTypeDescriptor misdeclared = new JobTypeDescriptor("widget.job", "Widget Job", "test type",
                List.of(), List.of(), List.of());   // emits=[] — under-declared, like report's old bug
        JobTypeCatalog catalog = JobTypeCatalog.of(List.of(misdeclared));

        var finding = catalog.flagUncatalogued("widget.job", "widget.produced");

        assertTrue(finding.isPresent(), "an emission not in the type's own declared emits must be flagged");
        assertTrue(finding.get().contains("widget.job"));
        assertTrue(finding.get().contains("widget.produced"));
    }

    @Test
    void doesNotFlagADeclaredEmission() {
        JobTypeDescriptor recon = new JobTypeDescriptor("recon.run", "Reconciliation Run", "test type",
                List.of(), List.of("recon.run.completed"), List.of());
        JobTypeCatalog catalog = JobTypeCatalog.of(List.of(recon));

        assertTrue(catalog.flagUncatalogued("recon.run", "recon.run.completed").isEmpty());
    }

    @Test
    void reportTypeNoLongerUnderDeclaresReportReady() {
        // Regression guard for the exact bug the plan names: report's emits must include REPORT_READY.
        JobTypeDescriptor report = new JobTypeDescriptor("report", "Report", "test type",
                List.of(), List.of("REPORT_READY"), List.of());
        JobTypeCatalog catalog = JobTypeCatalog.of(List.of(report));

        assertTrue(catalog.flagUncatalogued("report", "REPORT_READY").isEmpty());
    }
}
