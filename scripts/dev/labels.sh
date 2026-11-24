#!/usr/bin/env bash
# Creates or updates the labels the workflow uses (docs/workflow.md). Safe to run again.
# Usage: bash scripts/dev/labels.sh   (needs gh, signed in with access to the repository)
set -euo pipefail
repo="${GITHUB_REPOSITORY:-$(git remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##')}"
label() { gh label create "$1" --repo "$repo" --color "$2" --description "$3" --force; }

label "release:major"      "b60205" "Breaking change: next major version"
label "release:minor"      "d93f0b" "A feature: next minor version"
label "release:patch"      "fbca04" "A fix: next patch version (also the default without a label)"
label "release:none"       "c5def5" "Documentation or process only: the version stays"
label "risk:high"          "b60205" "Touches risky paths: Claude reviews it in CI, Opus checks those files"
label "changes-requested"  "e99695" "Claude's review asks for changes; the worker answers once, then adds re-check"
label "re-check"           "bfd4f2" "Ask Claude in CI to check the fixes, or to review a PR it has not reviewed"
label "needs-owner"        "d876e3" "The owner decides: a disagreement after the review round, or a finished plan to look at"
label "ready-to-merge"     "0e8a16" "The CI review found nothing blocking"
label "check-after-deploy" "1d76db" "Merged; the owner still checks it after deploy"
label "idea"               "0052cc" "A rough request: adding this starts a cloud session that writes the spec and builds it"
label "spec"               "0052cc" "A full spec: adding this starts a cloud session that builds it"
label "local"              "5319e7" "Built on the owner's machine at the next /drain, never in the cloud"
label "needs-info"         "d876e3" "Waiting for the owner's answer on the issue; put idea back after answering"
label "plan"               "0e8a16" "A plan issue: the order of work the dispatch step follows"
label "needs-rebase"       "fef2c0" "No longer merges cleanly with main: a session rebases it"
label "hold"               "ededed" "Do not merge this PR by itself"
