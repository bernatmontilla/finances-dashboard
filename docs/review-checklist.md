# Review checklist

Used by the `reviewer` subagent every worker runs (`.claude/agents/reviewer.md`) and by Claude's
review in CI (`.github/workflows/claude-review.yml`). The full rules are in `AGENTS.md`.

## Comment in this order, blocking issues first

1. **A wrong or silently outdated amount, lost owner input, or money data leaving the machine.**
   An amount that could be shown wrong, twice or as current when it is stale; a sync, import,
   restore or migration that could overwrite what the owner entered; any request to a host the
   installer did not configure; anything that could start a payment; a token, IBAN or amount in a
   log (`AGENTS.md`, hard rules 6 to 11).
2. **Security**: authentication, permission scopes, data leaving the system, secrets in code.
3. **Real data in the repository**: real names, addresses, emails, account numbers in tests,
   comments or docs. Only the fictional set in `AGENTS.md` is allowed.
4. **Breaking changes** to what other things depend on (`AGENTS.md`, hard rule 4).
5. **Missing or weakened tests**: no test for new behaviour or for a fixed bug. An existing assertion
   that is changed, loosened or deleted blocks, unless the spec says the behaviour changes and the
   PR description names it.
6. **New dependencies** that the PR description does not say were approved.
7. **Risk paths**: a new file or package that belongs under a `risk:*` glob in `.github/labeler.yml`
   but isn't. Without it, later changes to it get no review in CI.

## Skip

- Pure style preferences and naming opinions.
- Suggestions to add libraries or frameworks.
- Rewrites of code the PR does not touch.
