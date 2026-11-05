---
description: Smart commit — figure out the current state, get the change onto a properly named branch, and commit it. Stops at the local commit (no push, no PR).
argument-hint: "[<short description of the change>]  (optional — otherwise derived from the diff)"
---

# /commit — App8Cloud Android SDK smart commit

You are committing an in-progress change in the `appeight/app8-android-cloud-sdk`
repository. This is **just a commit** — with smart pre-commit logic around it:
it figures out what there is to commit, makes sure the work is on a branch whose
name actually describes it, then makes a clean Conventional Commit.

It is the **first part of `/land`** carved out on its own. It does **not** push,
open a PR, run tests, or merge. When the user later wants to ship the change,
they run `/land`, which picks up from the committed branch.

Follow this runbook **step by step**. The only state-changing operations are
creating/renaming a branch and committing — both happen only after the user
confirms.

Argument: `$ARGUMENTS` — an optional short description of the change. Use it to
seed the branch slug and commit message. If empty, derive both from the diff.

The command can be invoked from **any branch in any state**. Stage A reconciles
that before anything else.

---

## Asking the user

**Every** question to the user — without exception — goes through the
`AskUserQuestion` tool. **Never** end a message with a free-form prompt that
waits for the user to type "go", "yes", "ok", or an edit. This holds for bounded
gates *and* for confirming drafted text (branch name, commit message).

For drafted text: print the draft in the message body so it renders as readable
markdown, then call `AskUserQuestion` — first option **"Use as-is"**, and the
user picks **"Other"** to supply an edited version. The `AskUserQuestion` tool
is the **only** way this runbook pauses for input.

Gates in this runbook:

- **B.2 change type** — `feat` / `fix` / `docs` / `chore` / `refactor`
- **B.3 branch-name mismatch** — `rename` / `new branch` / `keep anyway`
  (only when the current branch name doesn't fit the change)
- **B.4 branch-name confirm** — confirm the final `<type>/<slug>` name
- **C.3 commit message confirm** — confirm the drafted Conventional Commit

---

## Stage A — Reconcile starting state

Run in parallel:

1. `git rev-parse --abbrev-ref HEAD` — record the **starting branch**.
2. `git status --porcelain` — capture the working-tree state.
3. `git stash list | head -5` — note pre-existing stashes so you don't confuse
   them with anything later.

Determine what there is to commit:

- **Dirty working tree** — these uncommitted changes are what gets committed.
  This is the normal case; do not treat it as an error. Continue.
- **Clean tree** — there is nothing to commit. `/commit` makes a commit; with no
  uncommitted changes there is nothing to do. Report that plainly and **stop**
  (if the user wants to ship already-committed work, that's `/land`).

Show the user `git status --short` and a one-line summary of what you detected.

---

## Stage B — Get onto a properly named branch

The branch name **must** describe the change actually being committed. A change
never goes onto a branch whose name doesn't fit it — not even an existing one
you happened to start on.

### B.1 — Understand the change

Inspect the diff being committed — `git diff` and `git diff --staged`. Form a
one-line description of what the change does. This drives both the branch name
and the commit message.

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

  If the name **fits** the change, keep it — skip to B.4 (confirm only).

  If it is a **mismatch**, do not silently commit onto it. Ask
  (`AskUserQuestion`), showing the current name and the ideal `<type>/<slug>`:
  - **rename** — rename the current branch in place (recommended when the branch
    is local-only and holds only this change).
  - **new branch** — create the ideal branch from the current HEAD, carrying the
    commits and working tree (use when the current branch is already pushed or
    shared and should stay intact).
  - **keep anyway** — the user judges the current name acceptable; continue on it.

> If the current branch also contains commits unrelated to this change, that is
> beyond a simple rename — **stop** and ask the user how to proceed rather than
> dragging unrelated work onto the wrong branch.

### B.4 — Confirm the branch name (`AskUserQuestion`)

Print the branch name that will be used and confirm it — first option
**"Use it"**, **"Other"** to supply a different `<type>/<slug>`. (When the
current branch already fits and you are staying on it, this still confirms it.)

### B.5 — Create or move onto the branch

Only if a new or renamed branch is needed:

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
  If the old branch name was already pushed to `origin`, note it in the summary:
  the stale `origin/<old-name>` remains until deleted (out of scope here — flag
  it so the user can clean it up, e.g. when they `/land`).

If the current branch already fits, no git command runs — you stay on it.

---

## Stage C — Commit

1. Review the diff: `git status --short` and `git diff`.
2. Stage **explicit paths only** — never `git add -A` / `git add .`. List each
   path you stage so the user can see it.
3. Draft a **Conventional Commit** message, print it in the message body, and
   confirm it via `AskUserQuestion` (first option **"Commit it"**, **"Other"**
   to supply an edited message):
   - Subject: `<type>: <imperative summary>` (≤ 72 chars), `<type>` matching B.2.
   - Optional body explaining the *why* if non-obvious.
   - End with the commit attribution lines the session provides (a
     `Co-Authored-By: Claude …` footer).
4. On confirmation, commit. **Do not push.**

If the change is large or mixed, splitting it into more than one commit is fine
— draft and confirm each. They squash at merge anyway, so optimize each message
for readability of the eventual PR.

---

## Stage D — Summary

Print a scannable recap:

1. **Starting state** — branch it was called from, working tree dirty/clean.
2. **Branch** — the `<type>/<slug>` branch used, created, or renamed (note the
   old name and any stale `origin/<old-name>` left behind).
3. **Staged** — the explicit paths that were committed.
4. **Commit** — the commit subject(s) and short SHA(s).
5. **Next** — the work is committed locally on `<branch>`. To push it, open a
   PR, test, and merge, run `/land`. If a stash from elsewhere is pending,
   remind the user to pop it.

---

## Guardrails (non-negotiable)

- **This skill stops at the local commit.** Never push, never open a PR, never
  run tests, never merge — that is `/land`'s job.
- **Never commit on `main`.** Always get onto a `<type>/<slug>` branch first.
- **Never lose uncommitted work.** `git checkout -b` carries the working tree;
  never stash-and-drop. Stage A accounts for the working tree first.
- **Never `git add -A` / `git add .`.** Stage explicit paths only.
- **Never auto-resolve anything** or rewrite existing commits.
- **Every question to the user uses `AskUserQuestion`** — bounded gates and
  drafted-text confirmations alike. Never end a message with a free-form "reply
  with … / type 'go'" prompt. See "Asking the user".
