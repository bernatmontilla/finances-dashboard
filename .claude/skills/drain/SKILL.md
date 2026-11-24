---
name: drain
description: Local night run - build every open issue labeled local on this machine, a few at a time, each in its own worktree, ending in the same kind of PR a cloud worker opens. Use only when the owner types /drain.
disable-model-invocation: true
---

# /drain: the local queue

For what a cloud session cannot reach (hardware on the desk, the home network) and for nights
when the cloud's limits are used up. Same agents and rules as the cloud (`docs/automation.md`);
only the orchestrating is different, because here one session runs several issues and subagents
cannot start subagents.

You orchestrate. You write no code, specs or reviews yourself. `/drain` takes every open issue
labeled `local`; `/drain 12 15` takes those issues whatever their labels.

1. **Queue.** `gh issue list --label local --state open` (GraphQL works on this machine). Skip an
   issue that already has a branch `claude/N-*` or an open PR. Nothing left: say so and stop.
2. **Per issue, at most 3 at a time**, each in its own worktree so the main checkout stays as the
   owner left it: `git fetch origin main`, then
   `git worktree add -b claude/N-topic ../<repo>-drain/N origin/main`.
   - No *Done when* heading in the issue: give its number, title and text to the `intake`
     subagent and act on its first line exactly as `docs/automation.md` says (`SPEC`: write the
     spec into the issue and go on; `QUESTIONS` or `REJECT`: comment, swap `local` for
     `needs-info` or remove it, drop the worktree, next issue).
   - Start the `worker` subagent, named `worker-N`, with: the issue number, the worktree's
     absolute path, and "you are a subagent". It builds, tests and opens the draft PR.
   - Then, in one message so they run side by side: `reviewer` on the branch; `risk-checker` on
     the changed files that match a `risk:*` path in `.github/labeler.yml`; `ui-checker` when
     the change shows on a page or screen.
   - Findings that block: send them to `worker-N` once. It fixes or answers, and pushes.
   - Put each review's first line in the PR description, check `mergeable`, mark the PR ready,
     remove the label `local`, and `git worktree remove` the worktree. From here CI, auto-merge
     and the review in CI take over, as for a cloud PR.
3. **Report** one line per issue as it moves ("#17: PR #40 ready, reviewer: Ready to merge"), and
   at the end what needs the owner.

Something fails twice for one issue: leave its worktree and branch, comment on the issue what
happened, and go on with the next. Never push to `main`, never merge.
