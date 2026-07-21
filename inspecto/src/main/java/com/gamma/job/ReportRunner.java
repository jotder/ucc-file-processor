package com.gamma.job;

/**
 * Narrow seam the job framework uses to run reports without depending on the service-side
 * {@code com.gamma.report.ReportService} — which aggregates {@code CollectorService} state and so
 * cannot sit below {@code service}. {@code ReportService} implements this (its typed
 * {@code statusReport()}/{@code serviceReport()} satisfy the {@code Object} returns via covariant
 * override); the composition root injects it into {@link JobService}. Returns are {@code Object}
 * because {@link ReportJob} serialises the result straight to JSON and never inspects the type.
 *
 * <p>Introduced (WS-D) to cut the last {@code job → report} compile edge so the engine cluster can
 * drop below {@code core}. See {@code docs/superpower/engine-cluster-extraction-plan.md}.
 */
public interface ReportRunner {

    /** Live status snapshot across all registered pipelines (a {@code ReportService.StatusReport}). */
    Object statusReport();

    /** Service-wide batch-audit report (a {@code ReportService.ServiceReport}). */
    Object serviceReport();
}
