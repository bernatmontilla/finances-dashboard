# Starting a project from this template

Extracted from the workflow of `bookkeeping-software-montilla` as of 1.0 (commit `87466e9`,
20 Nov 2026), with everything specific to that project taken out. Delete this file once the project is set up.

## Where each piece lives, and why

| Piece | Lives in | Reaches |
|---|---|---|
| Method skills: `test-first`, `root-cause-debugging`, `write-spec`, `plan-product` | your **claude.ai account** | chat, Projects, local Claude Code (synced), cloud sessions |
| Rules (`AGENTS.md`), agents and skills in `.claude/`, docs loop, GitHub templates and workflows | **this repository**, copied per project | local and cloud Claude Code, Gemini/Codex (they read `AGENTS.md`), GitHub |
| Three scheduled tasks per repository | **claude.ai/code/routines** ([docs/automation.md](docs/automation.md)) | start cloud sessions from labels and merges |
| Other people's skills a worker needs (design skills for a website, say) | copied into the project's `.claude/skills/` and committed (`npx skills add <repo> --skill <name>`) | that project, local and cloud |
| Plugins, rtk, anything in `~/.claude/` | your machine only | interactive local sessions; cloud sessions never see them |

Rule of thumb: improve a **method** in the account skill (every project gets it); improve the
**workflow plumbing** here in the template (new projects get it; old ones copy the change by hand
when they want it); keep **project facts** in that project's `AGENTS.md` and `docs/`.

## Checklist for a new project

1. **Create the repository** from this template (GitHub → *Use this template*), then clone it.
2. **GitHub side, one command**: `bash scripts/dev/setup-repo.sh` (try `--dry-run` first). It
   creates the labels, sets the merge settings (squash only, auto-merge allowed, merged branches
   deleted) and the ruleset *Protect main* (a pull request and a green `build` to reach `main`).
   None of that is copied by *Use this template*. It ends with the list of what is left by hand.
3. **Secrets** (the script prints the commands):
   - `AUTOMERGE_TOKEN`, a fine-grained token for this repository with Contents and Pull requests
     read/write, so an automatic merge still starts CI and a deploy on `main`.
   - `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token`, for Claude's review in CI of risky PRs.
     The Claude GitHub app must have access to the repository (github.com/apps/claude).
4. **Cloud environment** for workers: [docs/cloud-environment.md](docs/cloud-environment.md).
5. **The three scheduled tasks**: [docs/automation.md](docs/automation.md), then its *First things
   to test* with a throwaway issue.
6. **Planning round**: in a Claude Code session on the repository, *"Run a planning round with the
   skill plan-product."* It fills the TODOs (`grep -rn TODO .`: `AGENTS.md`,
   `scripts/dev/build.sh`, `.github/labeler.yml`, `docs/review-checklist.md`,
   `.claude/agents/risk-checker.md`, the toolchain step in `.github/workflows/ci.yml`) and writes
   `docs/product/`, the first issues and the plan. Until `labeler.yml` names this project's risky
   paths, put `hold` on PRs you want to read: everything else merges by itself.
7. Delete what the project does not need: `ui-checker` (nothing to look at), `risk-checker` and
   `claude-review.yml` (nothing risky, a plain website), `drain` (never local).
8. Label the plan's first rows `idea`. Fix whatever in this template got in the way, then copy
   that fix back to the template.

## Per kind of project

| Project | Keep | Add |
|---|---|---|
| App with data that matters (a hub, a finance dashboard) | everything | the invariants in `risk-checker`, wide risky paths, guard tests for the hard rules |
| Website | no `risk-checker`, no CI review; nearly everything auto-merges | `ui-checker`; design skills copied into `.claude/skills/` (from `emilkowalski/skills`: `emil-design-eng`, `break-ui`, `mobile-native`, `learn-taste`); a `DESIGN.md` |
| Device (an e-ink display) | everything except CI review, if nothing is risky | a host command that renders the screen to a PNG, so `ui-checker` and the PR can show it; rows that flash the board are `(local)` |
