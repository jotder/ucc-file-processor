# ADR-0007: Java 25 bytecode floor; GitHub Packages + GitHub Actions release

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

Consumers: UCC (Java 24 today), CVVE (greenfield), and CxO (Java 25 per its ADR-0003). A library's class-file version must be **≤ every consumer's JVM** — the floor is the *lowest* consumer JVM, not the highest. We must pick that floor and a publish channel. The org standardizes on GitHub.

## Decision

The kernel targets **Java 25** (`maven.compiler.release=25`) — the current LTS and CxO's baseline. Every consumer must run JVM ≥25; **UCC moves 24→25** as a prerequisite, sequenced as the first task of its U0 migration *before* it wires to the kernel. CVVE/CxO are greenfield at 25. Published to **GitHub Packages** via **GitHub Actions**: build+test on PR, deploy on tag `v*` using the built-in `GITHUB_TOKEN`. Consumers add the GitHub Packages repository and pin a version. Ring-1 remains zero-dependency (ADR-0001) regardless of target.

## Options Considered

### Option A: Floor at Java 17 (maximum compatibility)

| Dimension | Assessment |
|---|---|
| Compatibility | Widest |
| Features | Forgoes newer records/sealed/pattern ergonomics |
| Justification | Lowest-common-denominator with no live <21 consumer |

**Pros:** any modern consumer. **Cons:** compiles below every actual consumer for no live reason.

### Option B: Floor at Java 21 LTS

| Dimension | Assessment |
|---|---|
| Compatibility | Broad LTS |
| Alignment | Still below CxO's 25; UCC would also need to leave 24 anyway for uniformity |

**Pros:** LTS, broad headroom. **Cons:** compiles below our newest consumer (CxO 25) for no gain; CVVE greenfield can simply start at 25.

### Option C: Floor at Java 25 (chosen)

| Dimension | Assessment |
|---|---|
| Baseline | Current LTS; CxO's baseline |
| Uniformity | All three align on one modern version |
| Cost | UCC bumps 24→25 (minor) |

**Pros:** current LTS; one modern baseline for all three; UCC's 24→25 bump is minor and 25 compiles UCC's ported 24 code cleanly. **Cons:** forces UCC off 24 before consuming; excludes any <25 consumer.

## Trade-off Analysis

Forcing UCC's single-version bump versus a clean, modern, uniform baseline across all three agents. The bump is small (24→25, both recent), one-time, and sequenced safely ahead of integration; the uniformity payoff is permanent. The registry/CI half (GitHub Packages + Actions) is the lower-stakes, reversible part of this decision — recorded here for completeness — while the Java floor is the load-bearing, hard-to-reverse half.

## Consequences

- **Easier:** one language baseline; latest LTS features; simple GitHub-native release with no extra secret to manage.
- **Harder:** UCC must reach Java 25 before U1; no consumer below 25.
- **Revisit:** only if a future consumer is pinned below 25 — that would require a new ADR (multi-release jar or a lower floor). The registry choice can change without a new ADR.

## Action Items

1. `maven.compiler.release=25` in the parent pom.
2. GitHub Actions CI on Java 25; tag `v*` → `deploy` to GitHub Packages using `GITHUB_TOKEN` (`permissions: packages: write`).
3. Sequence UCC's 24→25 bump as the first U0 task, ahead of U1 wiring.
4. Fill the GitHub `<org>` into `distributionManagement` and consumer repository URLs.
