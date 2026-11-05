---
description: Land a change into main — reconcile branch state, branch if needed, commit, push, open a PR, run tests, and squash-merge.
argument-hint: "[<short description of the change>]  (optional — used to seed the branch slug and commit message)"
---

# /land — App8Cloud Android SDK change lander

You are landing a change into the open-source `appeight/app8-android-cloud-sdk` repository.
Follow this runbook **step by step**. Every state-changing or outward-facing
operation is gated by an explicit user confirmation — you must never lose the
user's uncommitted work, force-push, or merge without the user's go-ahead.

This repo is public and early-stage: no contributors depend on it yet, so a
maintainer landing their own change end-to-end (branch → PR → squash-merge) is
fine. See **Graduation** at the bottom — once real contributors arrive, this
fast-track stops.

Argument: `$ARGUMENTS` — an optional short description of the change. Use it to
seed the branch slug and commit message. If empty, derive both from the diff.

The command can be invoked from **any branch in any state**. Stage A reconciles
that before anything else.

---

## Asking the user

**Every** question to the user in this runbook — without exception — goes
through the `AskUserQuestion` tool. **Never** end a message with a free-form
prompt that waits for the user to type "go", "yes", "ok", or an edit (e.g.
"Reply with changes, or 'go'." is forbidden). If you need an answer, ask with
the tool; if you don't need one, don't stop.

This holds for the bounded-choice gates:

- **B.2 change type** — `feat` / `fix` / `docs` / `chore` / `refactor`
- **B.3 branch-name mismatch** — `rename` / `new branch` / `keep anyway` (only when the current branch name doesn't fit the change)
- **B.4 branch name** — confirm the final `<type>/<slug>`
- **B.5 sync with main** — `yes` / `no` (only when the branch base is behind origin/main)
- **C.3 commit message** — confirm the drafted message
- **E.3 PR title & body** — confirm the drafted PR text
- **F.2 test failure** — `fix now` / `merge anyway` / `abort`
- **G.1 final merge gate** — `merge` / `hold` / `abort`
- **On any merge conflict** — `resolved, continue` / `abort`

It holds equally for **open-ended text** — the branch slug, the commit message,
the PR title and body. Do not prompt for these in prose. Instead: draft the
text, print the full draft in the message body so it renders as readable
markdown, then call `AskUserQuestion` to collect the decision:

- First option **"Use as-is"** (or similar) — accept the draft unchanged.
- The user picks **"Other"** to type a revised slug / message / body.

The `AskUserQuestion` tool is the **only** way this runbook ever pauses for
input.

---

## Stage 0 — Preflight

Run in parallel and stop on any failure:

1. `gh auth status` — if not logged in, **stop** and tell the user to run
   `! gh auth login` in the prompt, then re-invoke `/land`. Do not continue.
2. `git remote get-url origin` — confirm it is `appeight/app8-android-cloud-sdk`. If the
   remote is a fork or something else, stop and ask the user.
3. `gh repo view --json defaultBranchRef -q .defaultBranchRef.name` — record the
   **default branch** (expected: `main`).

---

## Stage A — Reconcile starting state

Run in parallel:

1. `git rev-parse --abbrev-ref HEAD` — record the **starting branch**.
2. `git status --porcelain` — capture the working-tree state.
3. `git stash list | head -5` — note pre-existing stashes so you don't confuse
   them with anything later.

Determine what there is to land:

- **Dirty working tree** — these uncommitted changes are the change to land.
  This is the normal case; do not treat it as an error.
- **Clean tree, on a feature branch with unpushed commits** — those commits are
  the change to land. Continue.
- **Clean tree, on `main`, nothing unpushed** — there is nothing to land.
  Report that and stop.

Show the user `git status --short` and a one-line summary of what you detected.

---

## Stage B — Get onto a properly named branch

The branch name **must** describe the change actually being landed. A change
never goes onto a branch whose name doesn't fit it — not even an existing one
you happened to start on. Stage B always derives the correct name and either
confirms the current branch matches or moves the work onto a proper branch.

### B.1 — Understand the change

Inspect the full diff being landed — both committed work
(`git log --oneline main..HEAD`, `git diff main...HEAD`) and uncommitted work
(`git diff` / `git diff --staged`). Form a one-line description of what the
change does. This drives both the branch name and the commit message.

### B.2 — Pick the change type (`AskUserQuestion`)

Ask which Conventional-Commit type fits the change:

- **feat** — a new user-facing capability
- **fix** — a bug fix
- **docs** — documentation only (`README.md`, `docs/`, `CONTRIBUTING.md`, …)
- **chore** — tooling, CI, dependency, or housekeeping with no behavior change
- **refactor** — internal restructuring with no behavior change

The **ideal branch name** is `<type>/<slug>`, where `<type>` is this choice and
`<slug>` is 2–4 kebab-case words describing the change (seed from `$ARGUMENTS`
or the diff) — e.g. `feat/label-baseline`, `fix/expression-parser-crash`,
`docs/dsl-reference`.

### B.3 — Assess the current branch

- **On `main`** — you cannot commit here. Go to B.4 to create the branch.
- **On a feature branch** — judge whether its name fits the change. It is a
  **mismatch** if any of these hold:
  - the type prefix is wrong (branch is `feat/…` but B.2 says `docs`);
  - the slug describes different or unrelated work;
  - the slug is a generic placeholder (`wip`, `tmp`, `temp`, `test`, `dev`,
    `patch`, a bare date, the user's name, etc.).

  If the name **fits** the change, keep it — skip to B.5.

  If it is a **mismatch**, do not silently land onto it. Ask (`AskUserQuestion`),
  showing the current name and the ideal `<type>/<slug>`:
  - **rename** — rename the current branch in place (recommended when the branch
    is local-only and holds only this change).
  - **new branch** — create the ideal branch from the current HEAD, carrying the
    commits and working tree (use when the current branch is already pushed or
    shared and should stay intact).
  - **keep anyway** — the user judges the current name acceptable; continue on it.

> If the current branch also contains commits unrelated to this change, that is
> beyond a simple rename — **stop** and ask the user how to proceed rather than
> dragging unrelated work into the PR.

### B.4 — Create or move onto the proper branch

- **From `main`** (B.3) or **new branch** (B.3 mismatch):
  ```
  git checkout -b <type>/<slug>
  ```
  From `main` this carries any uncommitted changes onto the new branch; from a
  mismatched branch it also carries that branch's commits. Never stash-and-lose
  the working tree.
- **rename** (B.3 mismatch):
  ```
  git branch -m <type>/<slug>
  ```
  If the old branch name was already pushed to `origin`, note it: the stale
  `origin/<old-name>` remains until deleted. Offer to delete it after Stage D
  (`git push origin --delete <old-name>`).

### B.4a — Confirm the branch name (`AskUserQuestion`)

Confirm the final branch name before moving on, via `AskUserQuestion`: show the
proposed `<type>/<slug>`, offer **"Use it"** as the first option; the user picks
**"Other"** to supply a different slug. Never wait on a typed reply.

### B.5 — Sync the branch base with origin/main

1. `git fetch origin main`
2. `git merge-base --is-ancestor origin/main HEAD` — exit 0 means the branch
   already contains every commit on origin/main; if so, skip to Stage C.
3. If origin/main has commits the branch lacks, **ask** (`AskUserQuestion`):
   > origin/main has N new commit(s) the branch doesn't have. Merge origin/main
   > into this branch before opening the PR? `yes` / `no`
4. On **yes**: `git merge origin/main`. If conflicts appear, **stop** — show the
   conflicted files, let the user resolve and tell you to continue, or abort
   with `git merge --abort`. **Never auto-resolve conflicts.**

---

## Stage C — Commit

Only commit if there are uncommitted changes (Stage A's dirty tree). If the
change is already fully committed on a feature branch, skip to Stage D.

1. Review the diff: `git status --short` and `git diff`.
2. Stage **explicit paths only** — never `git add -A` / `git add .`. List each
   path you stage so the user can see it.
3. Draft a **Conventional Commit** message:
   - Subject: `<type>: <imperative summary>` (≤ 72 chars), `<type>` matching B.2.
   - Optional body explaining the *why* if non-obvious.
   - End with the commit attribution lines the session provides (a
     `Co-Authored-By: Claude …` footer).
   Print the drafted message, then confirm it via `AskUserQuestion` — first
   option **"Commit it"**, **"Other"** to supply an edited message. Never wait
   on a typed reply.
4. Commit. Do not push yet.

If the change is large or mixed, it is fine to make more than one commit — they
get squashed at merge anyway, so optimize each commit message for readability of
the eventual PR.

---

## Stage D — Push

```
git push -u origin <branch>
```

If B.4 renamed a branch that had already been pushed under its old name, delete
the stale remote branch now (confirm with the user first):

```
git push origin --delete <old-name>
```

If the push is rejected (non-fast-forward, the remote branch diverged), **stop**
and surface the error. Never use `--force` or `--no-verify` without explicit
user instruction.

---

## Stage E — Open the pull request

1. Draft a PR **title** — same Conventional-Commit form as the commit subject.
2. Draft a PR **body**. Include:
   - **What** changed and **why**.
   - Test note — what you verified, or that Stage F will run the suite.
   - End with:
     ```
     🤖 Generated with [Claude Code](https://claude.com/claude-code)
     ```
3. Print the drafted title and body, then confirm them via `AskUserQuestion` —
   first option **"Open the PR"**, **"Other"** to supply edits. Never wait on a
   typed reply.
4. Create the PR against `main`:
   ```
   gh pr create --base main --head <branch> --title "<title>" --body "<body>"
   ```
5. Capture and report the PR URL.

---

## Stage F — Test gate

`CONTRIBUTING.md` requires the full suite and CI to pass before a change merges.

### F.1 — Run the suite

Run the same gate as CI (`.github/workflows/ci.yml`), with an `app8-android-sdk` checkout next
to the repo so the composite build compiles against the engine (`scripts/fetch-engine.sh` clones
the tag equal to `SDKVersion.CURRENT` into `../app8-android-sdk` when it is missing):

```
scripts/fetch-engine.sh
scripts/check-version.sh
./gradlew --continue test :cloud:lint apiCheck :sample:assembleDebug
```

Then wait for the PR's CI run: `gh pr checks <n> --watch`. Both must be green.

If `apiCheck` fails, the public API changed: if intended, run
`./gradlew apiDump`, review the `cloud/api/cloud.api` diff, and commit it (Stage C);
otherwise fix the code. For rendering-path changes, also run the sample on the
`a8_pixel_9_api37` emulator (it renders its bundled `.a8pack` offline; see `CLAUDE.md`).

### F.2 — On failure (`AskUserQuestion`)

If tests fail, show the failing tests and ask:

- **fix now** — stop the merge; the user/you fix the failures, then re-commit
  (Stage C), push (Stage D), and re-run F.1. The PR updates automatically.
- **merge anyway** — only if the user explicitly accepts a red suite. Note it
  loudly in the final summary.
- **abort** — leave the PR open, stop here.

Do not proceed to Stage G until tests are green or the user explicitly chose
**merge anyway**.

---

## Stage G — Merge

> Note: GitHub does not allow approving your own PR, so there is no "approve"
> step for a maintainer's own change — the gate below *is* the review.

### G.1 — Final merge gate (`AskUserQuestion`)

Print a clean summary first (see Stage H), then ask one question:

- **question**: "Merge PR #<n> into `main`? Squash-merges and deletes the branch."
- **merge** — run the squash-merge below.
- **hold** — leave the PR open; print the URL and stop.
- **abort** — leave the PR open; print the URL and stop.

### G.2 — Squash-merge

On **merge**:

```
gh pr merge <n> --squash --delete-branch
```

Squash merge keeps `main` to one clean commit per PR. After merging, the local
branch is deleted by `--delete-branch`; switch back: `git checkout main && git pull --ff-only origin main`.

If the merge fails (branch protection, conflicts, checks pending), surface the
error — never retry with `--force` or `--admin` without explicit user
instruction.

---

## Stage H — Summary

Print a scannable recap:

1. **Starting state** — branch it was called from, working tree dirty or clean.
2. **Branch** — the `<type>/<slug>` branch used, created, or renamed (note the
   old name and whether a stale remote branch was deleted).
3. **Base sync** — what happened with origin/main (up-to-date / merged N).
4. **Commits** — the commit subject(s) landed.
5. **PR** — number, title, URL.
6. **Tests** — passed / failed (+ what), or "merged anyway" if F.2 chose that.
7. **Merge** — merged & branch deleted / held / aborted.
8. **Next** — if merged: `git checkout main && git pull --ff-only origin main`.
   If a stash from elsewhere is pending, remind the user to pop it.

---

## Guardrails (non-negotiable)

- **Merge only at the G.1 gate, only on explicit `merge`.** Anywhere else,
  merging is forbidden.
- **Never lose uncommitted work.** `git checkout -b` carries the working tree;
  never stash-and-drop. Stage A must account for the working tree first.
- **Never `git add -A` / `git add .`.** Stage explicit paths only.
- **Never force-push, never `--no-verify`, never `gh pr merge --admin`** without
  explicit user instruction.
- **Never auto-resolve merge conflicts.** Surface them and wait.
- **Cloud behavior ships on both platforms.** If the diff changes caching,
  networking, telemetry wire values or the offline bundle contract, confirm the
  matching `app8-ios-cloud-sdk` change exists (link it in the PR body) before
  merging; see `CONTRIBUTING.md`.
- **Tests must be green before merge** unless the user explicitly picks
  "merge anyway" at F.2.
- **We push branches to `origin`, we do not fork.** Maintainers have write
  access; forking is for external contributors.
- **Every question to the user uses `AskUserQuestion`** — bounded gates *and*
  drafted-text confirmations (slug, commit message, PR title/body). Never end a
  message with a free-form "reply with … / type 'go'" prompt. See "Asking the
  user".

---

## Graduation

This runbook lets a maintainer land their own change without external review.
That is appropriate **only** while the SDK has no outside users or contributors.
Once that changes, update this command and the repo:

- Enable branch protection on `main`: require a PR, require ≥1 approving review
  from someone other than the author, require the `CI / build` check to pass
  (`.github/workflows/ci.yml` already runs Stage F's gate on every PR).
- At that point `/land` should stop at Stage E (PR opened) and **not** self-merge
  — a second person reviews and merges.
