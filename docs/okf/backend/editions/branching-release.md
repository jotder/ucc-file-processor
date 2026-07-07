---
type: Concept
title: Branching & Release Policy
description: Versions are branches; editions are not. Merge-forward propagation, SemVer, Conventional Commits.
resource: docs/BRANCHING.md
tags: [branching, release, semver, conventional-commits, merge-forward]
timestamp: 2026-06-28T00:00:00Z
---

# Branching & Release Policy

Authoritative doc: `docs/BRANCHING.md`. Two axes, never confused:

* **Versions = git branches.** Active: `master` (newest mainline) + the current `N.x` (today `4.x`). Retired/
  EOL and frozen: `1.x`, `2.x`, `3.x` (no commits/pushes/tags; never a propagation target).
* **Editions = build flavors** (see [editions model](editions-model.md)) — never branches.

**Versioning**: SemVer `vMAJOR.MINOR.PATCH` + Conventional Commits — `fix:`→PATCH, `feat:`→MINOR,
`feat!`/`BREAKING CHANGE:`→MAJOR; `chore/docs/refactor/test/build/ci/perf/style/revert`→none. One version
spans all editions.

**Propagation = MERGE-FORWARD (oldest → master).** A `fix:` lands on the **oldest still-supported branch it
affects**, then merges forward up to `master` (`4.x → master`) — a fix may never silently regress in a newer
line. `feat:` goes to `master` only (empty merge-forward set). Refuse to commit/push/tag on retired
`1.x`/`2.x`/`3.x`.

This is enforced by three layers: a Claude Code hook (agent reminder), `.githooks/pre-push` (local block),
and CI `branch-policy.yml` (un-bypassable backstop).
