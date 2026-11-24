# The cloud environment for workers

Workers are Claude Code cloud sessions ([claude.ai/code](https://claude.ai/code)), one per issue.
Nothing of yours has to stay on, and the session builds and tests itself.

What a cloud session loads: this repository's `CLAUDE.md`/`AGENTS.md` and `.claude/` (agents,
skills, settings), plus the skills enabled on your claude.ai account (`test-first`,
`root-cause-debugging`, `write-spec`). It does **not** load plugins or anything in `~/.claude/` on
your machine, so everything a worker needs is either committed here or an account skill.

## Set it up once

1. At claude.ai/code choose this repository.
2. Environment selector → **New environment**:
   - **Name:** the repository's short name.
   - **Network access:** *Trusted* (package registries and GitHub; everything else blocked).
   - **Environment variables:** none. No real keys or data: workers use fictional data only.
   - **Setup script:** TODO, only if the toolchain is not pre-installed (Python, Node 20–22,
     Java 21, Go, Rust, Ruby, PHP, Docker are). Keep it under five minutes so it gets cached.
3. Start a session with *"Run `bash scripts/dev/build.sh` and tell me whether it passes."*
   If a download is blocked: Network access → *Custom*, tick *Also include default list*, add the
   host, start a new session.

## Start a worker

Workers start themselves when an issue is labeled `idea` or `spec` ([automation.md](automation.md)
has the three scheduled tasks to create). By hand it still works:

> Work on issue #N as the worker in `.claude/agents/worker.md`.

## Models

| Who | Model |
|---|---|
| Lead (planning rounds), `intake` | Opus or Fable |
| Worker, `reviewer`, `ui-checker`, CI review, dispatch | Sonnet |
| `explorer` | Haiku |
| `risk-checker` | Opus, only for `risk:*` files |
