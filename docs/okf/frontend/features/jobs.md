---
type: Feature
title: Jobs
description: Scheduled jobs and run history, with a run-detail dialog.
resource: inspecto-ui/src/app/modules/admin/jobs/jobs.routes.ts
tags: [feature, jobs, operations, schedules]
timestamp: 2026-06-28T00:00:00Z
---

# Jobs

Route `/jobs` (Operations nav group). Scheduled jobs in **standard** [data-tables](../design-system/data-table.md)
(`autoHeight`); a **job-runs dialog** shows run history. Backed by `JobsService`.
