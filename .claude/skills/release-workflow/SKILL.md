---
name: release-workflow
description: >
  MANDATORY checklist for committing, pushing, tagging, or releasing in this repo. MUST be applied
  BEFORE any git commit / push / tag. Encodes the binding branch & version policy from
  docs/BRANCHING.md: versions=branches (active: master + current N.x; retired/EOL: 1.x/2.x/3.x),
  editions=build flavors (never branches), SemVer + Conventional Commits, and the MERGE-FORWARD
  (oldest supported → master) propagation rule. Trigger on any git commit/push/tag, release, or
  branching question.
---

# Release & Branch Workflow (binding)

Canonical policy: [docs/BRANCHING.md](../../docs/BRANCHING.md). This skill is the operational
checklist. Enforced by three layers: a Claude Code hook (agent reminder), `.githooks/pre-push`
(local block), and CI `.github/workflows/branch-policy.yml` (un-bypassable backstop).
**One-time per clone:** `git config core.hooksPath .githooks`.

## The two axes — never confuse them

- **Versions = git branches.** Active: `master` (newest mainline) + the current `N.x` (today **`4.x`**).
  **Retired/EOL (FROZEN — no commits/pushes/tags, never a propagation target): `1.x`, `2.x`, `3.x`.`**
- **Editions (Personal / Standard / Enterprise) = build flavors** (Maven profiles + `ServiceLoader`
  modules + `-D` flags). **Editions are NEVER branches.**

## Versioning

SemVer `vMAJOR.MINOR.PATCH`. **Conventional Commits:** `fix:`→PATCH, `feat:`→MINOR,
`feat!:`/`BREAKING CHANGE:`→MAJOR; `chore/docs/refactor/test/build/ci/perf/style/revert`→none.
Scope encouraged (`fix(etl):`, `feat(ui):`). One version spans all editions; artifacts differ by
classifier (`-personal` / `-standard`).

## Propagation = MERGE-FORWARD (oldest → master)

A `fix:` lands on the **oldest still-supported branch it affects**, then merges forward to `master`
(`4.x → master`). A fix may never silently regress in a newer line. `feat:` goes to `master` only.

## MANDATORY checklist (every commit / push / tag, every branch)

1. **Classify** the change (Conventional Commit type) → SemVer effect + target line.
2. **Find the oldest supported branch** affected.
3. If it's a `fix:` and you're on a *newer* line → **STOP**, relocate the fix down to that branch first.
4. **Enumerate every supported branch** that still needs the change, **ask the user to confirm the
   merge-forward set**, then execute merges up to `master`.
5. **Refuse** to commit/push/tag on retired `1.x` / `2.x` / `3.x`. A security backport to an EOL line
   needs a **human** to set `UCC_RELEASE_GUARD_DISABLE=1` and document it — agents ask, never self-authorize.
6. **Editions** are build flavors — never create/push `personal`/`standard` branches.
7. **Tag** releases `vX.Y.Z` on the branch they ship from.

## Common commands

```bash
# Bug fix present in the shipped major:
git checkout 4.x && git checkout -b fix/<name>      # commit as fix(scope): …
git checkout master && git merge --no-ff 4.x && git push origin master   # merge-forward

# Feature (next release): branch from master, commit feat(scope): …, PR into master only.
```

End commit messages with the required co-author trailer when committing on the user's behalf.
