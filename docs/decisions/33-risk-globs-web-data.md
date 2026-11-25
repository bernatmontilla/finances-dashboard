# 33: `finances/web` and `finances/data` under `risk:high`

- Decision (lead): code under `src/*/java/finances/web/**` (controllers that change data, the
  same-origin filter, the import upload) and `src/*/java/finances/data/**` (where the data folder and
  the database are configured) belongs under `risk:high` in `.github/labeler.yml`. Before, a pull
  request that changed only those paths merged by itself without the review in CI (seen in 25, 30, 31).
- Only `.github/labeler.yml` changed: the two globs and the comment above the list.
- `.claude/agents/risk-checker.md` and `docs/review-checklist.md` do not list the paths (they point
  at `labeler.yml`), so nothing changed there.
- The labeler runs as `pull_request_target` with `main`'s `labeler.yml` (`docs/gotchas.md`): the new
  globs label only the pull requests after this one is merged, not this one.
