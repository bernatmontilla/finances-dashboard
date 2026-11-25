# Gotchas

Things that cost a session time once. One line each, grouped by area, read only the part about
what you are changing. Add a line when you hit a new one (in the same PR). When the same gotcha
bites twice, turn it into a guard test that scans the code or the docs and names file and line, and
mention the test here.

## Money and bank data

- A bank's "balance" comes in several types (booked, available, expected); store which one, and
  show the booked one unless a spec says otherwise.
- The same transaction can arrive first as pending and later as booked with another reference.
- SQLite allows one writer: keep write transactions short, and never hold one across an HTTP call.

## The target machine

- 2 GB RAM shared with other JVMs: the heap cap is real. Stream large result sets.
- arm64: a dependency with native code (sqlite-jdbc) must ship an aarch64 Linux binary.

## Tests and CI

- On a CI failure, read the check-run annotations:
  `gh api repos/{owner}/{repo}/check-runs/<job id>/annotations`.
- A test that depends on today's date breaks on the first of the month: build the data relative to
  now, not a fixed date.

## Pull requests and labels

- A PR that conflicts with `main` gets no `CI` and no *Claude review*, not even when it is marked
  ready: GitHub starts no `pull_request` workflow for it. Check `mergeable` before marking ready.
  After the rebase the push starts `CI` but not the review (it starts only on opened, ready and
  labelled): add `re-check` (remove it first if it is there) once the PR merges cleanly.
- The labeler runs as `pull_request_target`, which uses `main`'s `labeler.yml`: a PR that adds a
  risky path labels only the PRs after it is merged, not itself.

## Git and GitHub from a Claude cloud session

- GraphQL is blocked: `gh pr create`, `gh pr edit`, `gh pr ready`, `gh issue view` fail. Use REST:
  - read an issue: `gh api repos/{owner}/{repo}/issues/N`
  - open a draft PR: `gh api repos/{owner}/{repo}/pulls -f title=… -f head=… -f base=main -F body=@file -F draft=true`
  - labels: `gh api repos/{owner}/{repo}/issues/N/labels -f 'labels[]=release:patch'`
    (remove: `gh api -X DELETE repos/{owner}/{repo}/issues/N/labels/<name>`)
  - ready for review: `gh api -X POST repos/{owner}/{repo}/pulls/N/ccr/ready_for_review`
- Shallow clone: `git fetch origin main` before pushing, else the push re-sends everything (HTTP 413).
  Rebasing a PR branch: read its old tip with `git ls-remote` and push with
  `--force-with-lease=<branch>:<old sha>`.
- Never `pkill -f` with a pattern contained in the command itself: kill by PID.
