---
description: Cut a release of App8Cloud for Android on the shared release train — verify the version against the pinned engine, tag main, and let the Release workflow publish to GitHub Packages and create the GitHub release.
argument-hint: "[<version, e.g. 0.4.0>]  (optional — must equal SDKVersion.CURRENT; otherwise read from it)"
---

# /release — App8Cloud Android release cutter

You are cutting a release of the open-source `appeight/app8-android-cloud-sdk`
library. A release is an annotated **git tag** at `main`'s HEAD, pushed to
`origin`. The tag push starts `.github/workflows/release.yml`, which verifies
the version, builds and tests **against the published engine** (no composite
build), publishes `dev.app8:cloud` to GitHub Packages, and creates the **GitHub
release** with the tag message as its notes.

Follow this runbook **step by step**. Tagging is outward-facing and
irreversible in practice (a published Maven version cannot be republished), so
every outward step is gated by an explicit `AskUserQuestion` confirmation.

Argument: `$ARGUMENTS` — an optional version (e.g. `0.4.0`). It is only a
cross-check: the version is fixed by the release train, never computed from
commits here.

---

## The release train (version-sync rule)

`app8-dsl`, the engines (`app8-ios-sdk`, `app8-android-sdk`) and the cloud SDKs
(`app8-ios-cloud-sdk`, this repo) release under **one version**
(`app8-dsl/VERSIONING.md`). App8Cloud Android `X.Y.Z` bundles engine `X.Y.Z`,
like iOS App8Cloud pins `app8-ios-sdk` `exact: "X.Y.Z"`.

- **In this repo** the version lives in `SDKVersion.CURRENT`
  (`cloud/src/main/kotlin/dev/app8/cloud/internal/SDKVersion.kt`). Gradle reads it
  for the Maven version **and** for the exact `dev.app8:engine:X.Y.Z` pin, so the
  two cannot drift. Bump it with `/land` **before** `/release`; `/release` never
  edits it.
- **The engine is released first.** `appeight/app8-android-sdk` must have the
  tag `X.Y.Z` and its packages published; otherwise the pin cannot resolve.
- **The iOS twin** is `app8-ios-cloud-sdk` at the same version.
- Tags are SemVer with **no `v` prefix** (`0.4.0`).

---

## Asking the user

**Every** question to the user — without exception — goes through the
`AskUserQuestion` tool. **Never** end a message with a free-form prompt that
waits for the user to type "go", "yes", "ok", or an edit.

Gates in this runbook:

- **B.3 iOS twin** — `continue` / `abort` (only when the iOS cloud tag is missing)
- **B.4 version confirm** — confirm the train version
- **C.1 release notes** — confirm the drafted notes
- **D.1 final release gate** — `create` / `hold` / `abort`
- **E.4 workflow failure** — `rerun` / `stop`

For drafted text, print the draft in the message body, then call
`AskUserQuestion` — first option **"Use as-is"**, **"Other"** to edit.

---

## Stage 0 — Preflight

Run in parallel and stop on any failure:

1. `gh auth status` — if not logged in, **stop** and tell the user to run
   `! gh auth login`, then re-invoke `/release`.
2. `git remote get-url origin` — confirm it is `appeight/app8-android-cloud-sdk`.
3. `gh repo view --json defaultBranchRef -q .defaultBranchRef.name` — expected `main`.

---

## Stage A — Verify release-ready state

1. `git rev-parse --abbrev-ref HEAD` — if not on `main`, `git checkout main`.
2. `git status --porcelain` — must be clean; otherwise **stop** (`/land` it first).
3. `git fetch origin main --tags --prune`.
4. Compare local `main` to `origin/main`: behind → `git pull --ff-only origin main`;
   ahead or diverged → **stop**; equal → continue.
5. `gh run list --workflow ci.yml --branch main --limit 1 --json conclusion,headSha`
   — must be `success` for `main`'s HEAD; otherwise **stop**.

---

## Stage B — Verify the train version

### B.1 — Read the version and the engine release

```
scripts/check-version.sh --release <target>
```

`<target>` is `SDKVersion.CURRENT` (from `scripts/check-version.sh` without
arguments). The script checks SemVer, the sibling engine's `EngineVersion.CURRENT`
when `../app8-android-sdk` exists, that the tag equals the version, and that
`appeight/app8-android-sdk` has the tag `<target>`. On failure, **stop**: release
the engine first, or fix the version with `/land`.

If `$ARGUMENTS` differs from the target (ignoring a leading `v`), **stop**.

Confirm the engine package exists:

```
gh api orgs/appeight/packages/maven/dev.app8.engine/versions --jq '.[].name' | grep -x <target>
```

### B.2 — Fresh tag

1. `git tag --sort=-v:refname | head -1` — the latest tag (may be empty).
2. The target must not be a tag already and must be greater than the last one.
3. `git log <last-tag>..HEAD --oneline` — if empty, **stop**: nothing to release.

### B.3 — The iOS twin (`AskUserQuestion` when needed)

`git ls-remote --tags https://github.com/appeight/app8-ios-cloud-sdk.git refs/tags/<target>`.
If missing, ask: "iOS App8Cloud `<target>` is not tagged. Release Android first?"
— **continue** (record it for the summary) / **abort**.

### B.4 — Confirm the version (`AskUserQuestion`)

| | Version | State |
|---|---|---|
| app8-android-sdk (engine) | `<target>` | tag + package exist |
| app8-ios-cloud-sdk | `<target>` | released / pending |
| app8-android-cloud-sdk | `<target>` | to be tagged (prior: `<last-tag>`, N commits) |

Question: "Release App8Cloud Android `<target>` (bundles engine `<target>`)?"

---

## Stage C — Release notes

Draft from the Conventional-Commit subjects since the last tag:

```markdown
## <version>

Release train <version>: bundles dev.app8:engine <version>.

### Installation
implementation("dev.app8:cloud:<version>")

### Features
- <feat subject> (#PR)

### Fixes
- <fix subject> (#PR)

### Other
- <docs / chore / refactor / test subjects> (#PR)
```

Drop empty headings, strip the `type:` prefixes, keep the train line. Print the
draft, confirm with `AskUserQuestion`.

---

## Stage D — Final release gate (`AskUserQuestion`)

Print the summary, then ask: "Create release `<version>` — tag `<version>` at
`<short-sha>` on `main`, push it, and let the Release workflow publish
`dev.app8:cloud` and create the GitHub release?" — **create** / **hold** / **abort**.

---

## Stage E — Tag & publish

1. Write the notes to a temporary file; `git tag -a <version> -F <notes-file>`.
2. `git push origin <version>`.
3. Watch the workflow:
   ```
   gh run list --workflow release.yml --limit 5 --json databaseId,headBranch,status
   gh run watch <run-id> --exit-status
   ```
4. **On failure** (`AskUserQuestion`): show `gh run view <run-id> --log-failed`;
   **rerun** (transient only: `gh run rerun <run-id> --failed`) or **stop**. A
   partly published version cannot be republished: the fix is the next patch.
   If the failure is "could not resolve dev.app8:engine", the workflow token
   lacks read access to the engine package (package settings → Manage Actions access).
5. On success: `gh release view <version> --json url` and
   `gh api orgs/appeight/packages/maven/dev.app8.cloud/versions --jq '.[].name' | grep -x <version>`.

Never `--force` a tag, never move or delete one, never overwrite a release
without explicit user instruction.

---

## Stage F — Summary

1. **Released** — version and tagged commit.
2. **Train** — engine `<version>` (tag + package), iOS cloud `<version>` released
   or **still to be tagged**.
3. **Previous** — prior tag, commit count.
4. **Workflow** — run URL and result; `dev.app8:cloud:<version>` published.
5. **GitHub release** — URL.
6. **For Gradle clients** — `implementation("dev.app8:cloud:<version>")` (it brings
   `dev.app8:engine:<version>` with it).

---

## Guardrails (non-negotiable)

- **One version across the train**: `SDKVersion.CURRENT` = the engine pin = the
  tag = the iOS cloud version. Fixing it is a `/land`, not a `/release`.
- **The engine release exists before this one.**
- **Release only from a clean `main` equal to `origin/main`, with green CI.**
- **Annotated tags, SemVer, no `v` prefix; never reuse, move or force a tag.**
- **Tag & publish only at the D.1 gate, only on explicit `create`.**
- **Every question uses `AskUserQuestion`.**
