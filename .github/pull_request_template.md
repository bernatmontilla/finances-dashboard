<!-- Title = the squash commit on main: "fix(#12): …" / "feat(#14): …" / "docs: …" -->

Fixes #<!-- one "Fixes #N" per issue -->

## What and why
<!-- Two or three sentences. Fictional data only (AGENTS.md). -->

## Decisions, assumptions and differences from the spec
<!-- docs/decisions/<issue>-<topic>.md, or "none". What you had to assume, what you did differently
     from the spec and why, what you left out on purpose ("skipped: X, add when Y"). -->

## After deploy, check
<!-- What the owner looks at to see it works; add the label check-after-deploy. Or "nothing". -->

## Checklist
- [ ] Release label set: `release:patch` (fix) · `release:minor` (feature) · `release:none` (docs/process)
- [ ] `bash scripts/dev/build.sh` passed locally (or: CI only, because …)
- [ ] Own review, first line of each: `reviewer`: … · `risk-checker` (risky files): … · `ui-checker` (visible change): …
- [ ] No existing assertion changed or deleted, or the spec changes that behaviour: …
- [ ] What other things depend on (AGENTS.md hard rule 4): untouched / changed backwards-compatibly: …
- [ ] No new dependency, or it was approved in the issue
