# Branch, Version & Release Strategy — STRICT (humans + agents)

> **This is binding.** Agents do most of the coding here; this policy is enforced both by
> `.claude/hooks/pre-tool-git-release-guard.sh` (on every `git commit` / `git push` / `git tag`)
> and by the mandatory section in [`CLAUDE.md`](../CLAUDE.md). Read it before you commit.

---

## 0. The two axes (do not confuse them)

| Axis | Mechanism | Example |
|---|---|---|
| **Versions / releases** | **git branches** | `master`, `4.x` |
| **Editions** (Personal / Standard / Enterprise) | **build flavors** — Maven profiles + `ServiceLoader` modules + `-D` flags | `mvn -Pedition-standard package` |

**Editions are NEVER branches.** There is no `personal` or `standard` branch. An edition is *which
modules get assembled* from the same commit. See the editions plan for the assembly detail.

---

## 1. Branch topology

### Active (supported — may receive commits)
- **`master`** — the mainline / newest supported line. All new features land here; it is the forward
  end of every merge chain. HEAD of the current development version.
- **`<N>.x`** — the maintenance branch for the **current** major (today: **`4.x`**). Receives `fix:`
  backports for the shipped major; tagged `vN.y.z` on release.

### Retired (FROZEN — end-of-life, NO commits/pushes/tags, NEVER a propagation target)
- **`1.x`, `2.x`, `3.x`** — read-only history only. The release guard hard-blocks any
  commit/push/tag on these. Do not branch new work from them.

> Going forward, the support window is **the current `N.x` + `master`**. When a new major is cut, a new
> `<N+1>.x` branch is created from `master`; the previous major's `<N>.x` may stay supported for a
> defined window, then moves to *Retired* (update the retired list in the hook + this doc).

---

## 2. Versioning — SemVer + Conventional Commits

Releases are SemVer, tagged `vMAJOR.MINOR.PATCH` (e.g. `v4.0.0`). One version spans all editions;
artifacts are differentiated by classifier (`-personal`, `-standard`), **not** by version.

Commit messages use **Conventional Commits** (already the repo convention):

| Type | SemVer effect | Where it lands |
|---|---|---|
| `fix:` | PATCH | oldest supported affected branch → merge-forward to `master` |
| `feat:` | MINOR | `master` only (next release) |
| `feat!:` / `BREAKING CHANGE:` | MAJOR | `master`; cut a new `<N+1>.x` line at release |
| `chore:` / `docs:` / `refactor:` / `test:` | none | usually `master`; backport only if it unblocks a `fix:` |

Scope is encouraged (`fix(etl):`, `feat(ui):`, `fix(security):`).

---

## 3. The propagation rule — MERGE-FORWARD (oldest → master)

> **A fix is made on the OLDEST still-supported branch it affects, then merged forward up the chain to
> `master`.** A fix can never silently regress in a newer line.

```
        fix lands here
             │
            4.x  ───merge──▶  master
        (current major)      (newest)
```

- Bug exists in the **released** major (`4.x`): commit the `fix:` on `4.x`, then
  `git checkout master && git merge --no-ff 4.x`.
- Bug exists **only on `master`** (unreleased code): fix on `master` only.
- **Never** start a fix on `master` if the bug also lives in `4.x` — STOP and relocate it down to `4.x`
  first, then merge forward. (Cherry-pick *down* is the wrong direction here.)
- When future lines exist, the chain extends: `4.x → 5.x → master`. Always end at `master`.

Features (`feat:`) do **not** get back-ported — they go to `master` for the next minor, unless the
release owner explicitly designates a maintenance feature release.

---

## 4. MANDATORY commit / push checklist

Run this **every time**, on any branch, before `git commit` / `git push` / `git tag`. The release-guard
hook injects it automatically and the agent must resolve it **and confirm the propagation set with the
user** before pushing.

1. **Classify** the change (Conventional Commit type) → determines SemVer + target.
2. **Locate the oldest supported branch** the change affects.
3. If it's a `fix:` and you are on a *newer* branch than the oldest affected line → **STOP**, relocate
   the fix to that older branch, commit there.
4. **Enumerate every supported branch** that still needs the change and **ask the user to confirm** the
   merge-forward set, then execute the merges up to `master`.
5. **Refuse** to commit/push/tag on retired lines `1.x` / `2.x` / `3.x`.
6. **Editions** are build flavors — do not create or push edition branches.
7. **Version + tag**: bump per SemVer; tag `vX.Y.Z` on the branch the release ships from.

---

## 5. Day-to-day workflow

```bash
# Feature (next release)
git checkout master
git checkout -b feat/<short-name>
# …code…  → commit as  feat(scope): …
# PR → squash/merge into master

# Bug fix present in the shipped major
git checkout 4.x
git checkout -b fix/<short-name>
# …code…  → commit as  fix(scope): …
# PR → merge into 4.x, then merge-forward:
git checkout master && git merge --no-ff 4.x && git push origin master
```

CI (`.github/workflows/ci.yml`) must build **every edition** and run tests on each PR, so a change that
breaks the Personal build or the Standard auth path fails before merge.

---

## 6. Release process

```bash
# On the line you are releasing (master for a new minor/major, N.x for a patch):
mvn -q org.codehaus.mojo:versions-maven-plugin:2.16.2:set \
    -DnewVersion=<X.Y.Z> -DprocessAllModules -DgenerateBackupPoms=false
git commit -am "chore(release): vX.Y.Z"
git tag -a vX.Y.Z -m "vX.Y.Z — <summary>"
git push origin <branch> --tags

# Build + publish both edition artifacts from the tagged commit:
#   mvn -Pedition-personal package   → file-processor-X.Y.Z-personal.jar
#   mvn -Pedition-standard package   → file-processor-X.Y.Z-standard.jar
gh release create vX.Y.Z --target <branch> \
    --notes-file <notes> '<personal.jar>' '<standard.jar>'

# Bump the line back to the next -SNAPSHOT:
mvn ...:set -DnewVersion=<next>-SNAPSHOT ... && git commit -am "chore: begin <next> development"
```

After a new MINOR/MAJOR on `master`, if it cuts a new major, create the maintenance branch:
`git checkout -b <N+1>.x && git push -u origin <N+1>.x`.

---

## 7. Exceptions

A genuine security backport to an EOL line, or any other deviation, requires a **human** to override the
guard with `UCC_RELEASE_GUARD_DISABLE=1` for that command and to record the exception in the PR/commit
body. Agents must not set this flag on their own — they must ask.
