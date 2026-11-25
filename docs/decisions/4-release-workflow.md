# #4 Release on every merge to main

- **Trigger.** `.github/workflows/release.yml` starts when the workflow `CI` has finished with
  success for a *push* to `main` of this repository (`workflow_run`). A pull request never starts it,
  and as a `workflow_run` workflow it runs from `main`'s copy, so a branch cannot change how it is
  released. It always runs on `ubuntu-latest`; `RUNS_ON` is not used. A guard test
  (`GuardTest.theReleaseWorkflowKeepsItsPromises`) checks that, the permissions and the asset names.
- **Permissions.** `permissions: {}` for the workflow, `contents: write` on the one job. The repository
  is public, so reading the pull request of a commit needs no further scope.
- **The pull request of a merge.** `GET /repos/{repo}/commits/{sha}/pulls`, keeping the merged pull
  request into `main` whose `merge_commit_sha` is that commit. None (a direct push) or no `release:*`
  label: nothing is published and the job summary says so. `release:none`: the same.
- **The version.** The last release is the highest tag `vMAJOR.MINOR.PATCH`. `release:major` gives
  `MAJOR+1.0.0`, `release:minor` gives `MAJOR.MINOR+1.0`, `release:patch` gives
  `MAJOR.MINOR.PATCH+1`. With no release yet the first one is `0.1.0`, whatever the level. When a pull
  request has several `release:*` labels the highest level counts. The job fails, rather than reuse
  a version, if the tag exists.
- **Order.** The `release` concurrency group runs one job at a time and does not cancel a running
  one. GitHub keeps only the newest waiting run, so each run works through *every* first-parent commit
  since the last release tag, oldest first, and builds each one at its own commit. A replaced run
  loses nothing, and two merges never get the same version. Before the first release only the commit CI
  built counts; the older history is not released.
- **The version in the jar.** `pom.xml` has `<version>${revision}</version>` and the property
  `revision` = `0.1.0-SNAPSHOT`, which is what a local build shows. The workflow sets
  `MAVEN_ARGS=-Drevision=<version>` for `bash scripts/dev/build.sh`; `build-info` puts it in
  `META-INF/build-info.properties`, which `/health` and the overview page read. The workflow
  checks that line in the jar before it publishes.
- **Assets and notes.** Exactly `finances-dashboard.jar` and `finances-dashboard.jar.sha256`, the
  latter written by `sha256sum` in the same folder (so `sha256sum -c` works where it is downloaded)
  and verified before the release is created. Notes are the pull request's title, an empty line and
  its URL.
- **Known limit.** A merge made with the workflow's own token starts no workflow (see
  `auto-merge.yml`): without the secret `AUTOMERGE_TOKEN` an automatically merged pull request is
  not released. A risky pull request is merged by the owner, which does start it.
- **A merge that does not build** is skipped (named in the job summary) when it is not the commit CI
  just built, so one broken merge never stops the releases after it; the next run tries it again and
  skips it again until a release tag lies after it. For the newest commit the job fails.
- **The token** is in the environment of the one step that calls `gh`, and the build runs with
  `env -u GH_TOKEN`, so no Maven plugin or test can read a token that can write releases. Not done:
  pinning the actions to commit SHAs (the other workflows use tags too).
- **Known limits.** Before the first release only the newest commit counts, so if two merges arrive
  at once and GitHub replaces the first one's waiting run, the first is not released. When one pull
  request has `release:none` and another `release:*` label, the other label wins.
