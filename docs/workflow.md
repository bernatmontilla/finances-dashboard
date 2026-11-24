# Workflow: plan, build, review, merge

Two loops. The **planning round** is where the owner thinks; it happens a few times per project.
The **issue loop** runs by itself for every issue. The rules are in [AGENTS.md](../AGENTS.md);
what starts automatically and how to set it up is in [automation.md](automation.md).

## Who does what

| Who | Where | Does |
|---|---|---|
| **Owner** | GitHub, phone is fine | Decides in planning rounds, files and labels issues, answers `needs-info`, merges risky PRs, does the acceptance check |
| **Lead** (Opus or Fable) | an interactive Claude Code session on this repository | Runs the planning round with the owner (skill `plan-product`), keeps `docs/product/` and [NEXT.md](NEXT.md) |
| **Intake** (Opus subagent) | inside the worker's session | Turns a rough issue into a spec, or asks, or rejects |
| **Worker** (Sonnet) | a cloud session per issue, started by a label; or `/drain` locally | Builds one issue, tests, opens the PR, reviews it with subagents |
| **Reviewer in CI** (Sonnet, Opus `risk-checker`) | GitHub Actions | Reviews ready PRs with a `risk:*` label |
| **Dispatch** | a cloud session after each merge | Starts the next rows of the plan |

## The planning round (with the owner)

For a new project, and for every rigorous round on an existing one. In an interactive Claude Code
session on the repository (cloud or local), model Opus or Fable: *"Run a planning round with the
skill plan-product."* It ends with these, committed through one PR:

1. `docs/product/CURRENT.md`, for an existing app: what is there, read from the code.
2. `docs/product/PRODUCT.md`: purpose, capabilities, what must always hold, non-goals, words.
3. Stack and architecture decisions in `docs/decisions/`, and the TODOs of `AGENTS.md`,
   `labeler.yml`, the review checklist and `risk-checker` filled in.
4. `docs/product/ROADMAP.md`: milestones as usable slices, the next one with an acceptance list.
5. The next milestone's issues (rough, unlabeled) and its **plan issue** with the order of work.

Then the owner labels the plan's first rows `idea`, and the issue loop takes it from there, row by
row, until the plan is done and asks for the acceptance check. Feedback from that check becomes
new issues or the next planning round.

## The issue loop (by itself)

1. **Start.** An issue gets `idea` (rough) or `spec` (full): filed by the owner with a template,
   or labeled by dispatch when its row's turn comes. A cloud session starts.
2. **Spec.** For `idea`: `intake` writes the spec into the issue, against the code as it is now.
   If only the owner can decide something, the questions go on the issue with `needs-info`.
3. **Build.** The worker checks the spec, branches `claude/N-topic`, builds test first, opens a
   **draft** PR (no CI on drafts: the local build is the gate).
4. **Own review.** `reviewer`, `risk-checker` on risky files, `ui-checker` on visible changes.
   The worker fixes what they find and marks the PR **ready**. CI builds it.
5. **Merge.**
   - **No `risk:*` label**: merged automatically once CI is green.
   - **`risk:*`** (set by path, `.github/labeler.yml`): Claude reviews it in CI and labels it
     `ready-to-merge` or `changes-requested`. Changes requested starts a session that fixes or
     answers once and adds `re-check`; a disagreement left over becomes `needs-owner`. The owner
     merges.
   - A PR that stops merging cleanly gets `needs-rebase`, which starts a session that rebases it.
6. **Next.** The merge starts dispatch, which ticks the row and labels the next free ones.
7. **Check.** PRs with something to look at after deploy carry `check-after-deploy`. If it breaks:
   **Revert** on the merged PR, merge the revert, open an issue.

What reaches the owner: `needs-info` issues, risky PRs, `needs-owner`, and finished plans.
One search shows all of it: `is:open label:needs-info,needs-owner,ready-to-merge,changes-requested`.

## Local nights

Issues labeled `local` are never built in the cloud. In a terminal on the owner's machine, in the
repository: `/drain`. Same agents, same PRs. See [automation.md](automation.md).

## Labels

| Label | Set by | Means |
|---|---|---|
| `idea`, `spec` | owner (templates) or dispatch | Start now, in the cloud: write the spec and build / build |
| `local` | owner or dispatch | Build at the next `/drain` on the owner's machine |
| `needs-info` | intake or worker | The owner answers on the issue, then puts `idea` back |
| `plan` | lead | A plan issue with the order of work |
| `release:patch` / `release:minor` / `release:none` / `release:major` | worker (from the spec) | fix / feature / docs or process / breaking |
| `risk:high` | automatically, by path | No auto-merge: Claude reviews in CI, Opus checks those files, the owner merges |
| `ready-to-merge`, `changes-requested`, `needs-owner` | Claude review | Where the review in CI stands |
| `re-check` | worker or owner | Ask Claude in CI to check the fixes, or to review a PR it has not reviewed |
| `needs-rebase` | automatically | No longer merges cleanly; a session rebases it |
| `hold` | owner | Do not merge this PR by itself |
| `check-after-deploy` | worker | Something to look at after the release |

Create them all with `bash scripts/dev/labels.sh`.

## Grow it when the project needs it

All of these exist in Boekhouding to copy from: a required `claude-review` status with a gate that
survives rebases, versions worked out from the `release:*` labels on merge, a monthly audit skill,
a self-hosted runner switch (`RUNS_ON`), and a capture script that photographs every page in every
state on a throwaway instance for a design and accessibility audit (`scripts/dev/ui-capture/`).
