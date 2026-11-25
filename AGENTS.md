# AGENTS.md — instructions for AI coding agents (Claude, Gemini, any other)

This is the single source of instructions for every AI working on this repository. `CLAUDE.md`
imports it; other agents (Gemini, Codex) read `AGENTS.md` themselves. Change rules here, never in a
tool-specific file.

## What this is

A self-hosted personal finances dashboard for one person: bank balances and transactions
(Enable Banking, CSV or by hand), what is really free to spend after taxes and reservations,
categorised spending, fixed costs, budgets, savings, investments and debt. Java 25, Spring Boot,
Thymeleaf, SQLite with Flyway, Maven; one jar run as a systemd service on a small low-memory machine (a
single-board computer is the reference target), bound to localhost and published on a private
network.
The repository is public and holds code only. What beats everything else: **the numbers shown
are right and honestly dated, and nobody's real financial data or setup ever reaches this
repository.**

Read first, every session (short on purpose; read the rest only when the work needs it):
- The issue you work on: it is your spec.
- `docs/NEXT.md`: what is current. `docs/workflow.md`: the loop every issue goes through.
- `docs/gotchas.md`: the part about what you are changing.

When needed: `docs/product/PRODUCT.md` (what this is and is not), `docs/decisions/` (why things are
the way they are; search by issue number or class name, don't read it whole), `README.md` (how the
app is used).

## Roles

- **Lead** (Opus or Fable, an interactive session with the owner): runs planning rounds (skill
  `plan-product`), writes `docs/product/`, the plan issues and `docs/NEXT.md`, decides architecture.
- **Intake** (`.claude/agents/intake.md`, Opus): turns a rough issue into a spec against the code
  and `docs/product/PRODUCT.md`, or asks the owner on the issue, or rejects it.
- **Worker** (Sonnet, `.claude/agents/worker.md`; a cloud session per issue started by a label, or
  the local `/drain`): checks the spec against the code, builds one issue on its own branch,
  tests, opens the PR and reviews it with the `reviewer`, `risk-checker` and `ui-checker`
  subagents. If the spec is wrong or misses something that changes the design, it stops and asks
  on the issue instead of redesigning.
- **Reviewer in CI** (`.github/workflows/claude-review.yml`): reviews ready PRs that touch risky
  paths (a `risk:*` label from `.github/labeler.yml`). It only reads; the workflow posts.
- **The owner** decides in planning rounds and merges risky PRs. A PR without a `risk:*` label
  merges by itself when CI is green, so a path that can break what must never break belongs in
  `.github/labeler.yml`. One review round per PR: the worker fixes what it agrees with and answers
  the rest with reasons, then adds `re-check`. Remaining disagreements go to the owner.
- Two agents never work on the same branch at the same time. What starts by itself and when:
  `docs/automation.md`.

## Git workflow

The order of the steps is in [docs/workflow.md](docs/workflow.md).

- **Never push to `main`, never force-push `main`.** Every change goes through a PR, squash-merged.
- Branch names: `claude/<issue>-<topic>` (e.g. `claude/14-export-csv`), `docs-<topic>` for
  documentation only.
- Open the PR as a **draft** as soon as the branch is pushed, from `.github/pull_request_template.md`,
  with one `Fixes #N` per issue and the `release:*` label the spec names. CI does not build drafts:
  your own local build is the gate. Mark it ready only after the local build passed and your own
  `reviewer` subagent ran, with its verdict in the PR description.
- **One topic per PR.** Several small, related issues may share one PR (one `Fixes #N` each, one
  commit per issue so each can be reverted alone) when none of them touches a `risk:*` path; the
  lead says so in the specs.
- Before marking a PR ready, check that it merges cleanly: a conflicting PR gets no CI and no review.
- Write a PR's design decisions in a new file `docs/decisions/<issue>-<topic>.md`. Never edit shared
  lines of `docs/NEXT.md` in a feature PR (the lead does that), so PRs don't conflict.
- Commit messages: a short summary line, then why. No `Co-Authored-By:` line, no session link and
  no "Generated with" line in commits or PR descriptions (`.claude/settings.json` switches them off).

## Build and test

```bash
bash scripts/dev/build.sh       # what CI runs; must pass locally before a PR is marked ready
```

One test class while drafting: `./mvnw -q test -Dtest=ClassName`. The full build should stay
under three minutes; say so in the PR if a change makes it slower. (Both exist from M0 on.)

If your environment cannot download the dependencies, push, mark the PR ready (a draft gets no CI)
and let CI be the compiler; say so in the PR description. Never mark work as done while CI is red.
In a Claude cloud session GitHub's GraphQL is blocked: use the REST calls in `docs/gotchas.md`.

Use the `explorer` subagent for searches across many files, so your own context stays small.

## Hard rules

1. **No real data anywhere in the repository**: not in tests, fixtures, comments, docs, issues or
   PR descriptions, and never in a prompt. Use the fictional set below. If you find real data, flag it.
   That includes your own infrastructure: host and network names, home-folder paths with a name in
   them (write `<host>`, `~`). A dev tool that starts the app must start it on its own empty data
   folder, never near the real one.
2. **No new dependencies** without the owner's explicit approval in the issue or PR.
3. **Never change, loosen or delete an existing assertion** to get a green build, unless the spec
   changes that behaviour; say so in the PR.
4. **Keep what other things depend on**: the after-tax contract
   (`docs/contracts/after-tax-source.md`), merged database migrations (never edited, only added),
   the backup file format (an older backup always restores), the release asset names
   `finances-dashboard.jar` and `.sha256` that `deploy/update.sh` fetches, the health endpoint,
   the configuration property names and the layout of the data folder. Change them only
   backwards-compatibly, and say so in the PR.
5. Do not touch deployment, `.github/workflows/` or `.claude/` unless the issue is about it.
6. **Read-only**: no code that moves money or starts a payment, and no payment scope requested
   from a bank API.
7. **Nothing leaves the machine** except through `finances.outbound`, to the hosts the installer
   configured (Enable Banking, the after-tax source, the notification channel). No telemetry; no
   font, script, stylesheet or image loaded from another host.
8. **Money is integer cents (`long`) with a currency**: never `double`, `float` or decimal
   arithmetic on amounts. Euro only; another currency is refused, not converted. Times are stored
   in UTC, booking dates as plain dates.
9. **No figure without its age**: every amount that comes from a source is shown with when it was
   last correct, and as stale when the source failed, expired or is too old.
10. **What the owner entered is never lost or overwritten** by a sync, an import, a restore of the
    same data or a migration; reading the same data twice stores nothing twice.
11. **Secrets and amounts stay out of logs and error messages**: no token, key, IBAN, counterparty
    or amount is logged. Secrets are read from the data folder only.

The same six product invariants are in `docs/product/PRODUCT.md`; rules 6 to 11 are how they
bind the code.

## Fictional test data (use these)

| What | Value |
|---|---|
| The owner of an installation | Sam Voorbeeld |
| Other people | Robin Example, Alex Sample |
| Banks | Example Bank, Sample Savings Bank (never a real bank name in tests or fixtures) |
| Own IBANs | `NL51XMPL0000000001` (private current), `NL24XMPL0000000002` (private savings), `NL94XMPL0000000003` (business current), `NL67XMPL0000000004` (loan) |
| Counterparty IBANs | `NL10SHOP0000000101`, `NL80SHOP0000000102`, `NL53SHOP0000000103` |
| Counterparties | Corner Grocery, Example Energy, Example Telecom, Example Insurance, Example Landlord, Example Gym, Example Client BV |
| Amounts | round, obviously invented (for example 12.34, 100.00, 1,234.56) |
| Hosts and paths | `<host>`, `~`, `example.com`, port 8080 in examples only |
| Emails | anything `@example.com` |
| Public names | Real names may stay only where the product is about them: Enable Banking, Tailscale, GitHub, systemd. Never a real person, bank, shop, employer, client, street, town, host name or user name. |

## Code conventions

- Every bug fix starts with a test that reproduces the bug (skill `test-first`), and you find the
  cause before you fix (skill `root-cause-debugging`).
- Use what exists before you add something new: a helper in this codebase, then the standard
  library, then a dependency that is already installed, then the least code that works.
- A rule that can be checked by reading files (no inline scripts, no real data in docs, every
  text in every language) gets a guard test, so the build enforces it instead of the review.
- Domain rules live in the domain packages under `finances` (`money`, `accounts`, `sync`,
  `importing`, `backup`), never in controllers or templates. A calculation that ends up on a
  page has a unit test that does not start the web layer.
- Every text a user sees is in `messages.properties` (English), never in a template or in Java;
  a guard test enforces it.
- Every page works at phone width (360 px) without horizontal scrolling, and with the browser's
  dark mode.
- The words of `docs/product/PRODUCT.md` (*scope*, *kind*, *source*, *reservation*, *stale* …)
  are the names in code, issues and the interface. Do not invent synonyms.

## Reviewing a PR (any AI)

Comment in the order of [docs/review-checklist.md](docs/review-checklist.md), blocking issues first.
Skip pure style preferences.
