# Review checklist

Used by the `reviewer` subagent every worker runs (`.claude/agents/reviewer.md`) and by Claude's
review in CI (`.github/workflows/claude-review.yml`). The full rules are in `AGENTS.md`.

## Comment in this order, blocking issues first

1. **TODO: the worst thing that can go wrong in this project** (for example "the books could become
   wrong", "a user's data could be lost"). Make this line concrete once you know.
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
