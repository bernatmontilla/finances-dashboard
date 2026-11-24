#!/usr/bin/env bash
# Sets up the GitHub side of a repository made from this template (SETUP-FROM-TEMPLATE.md):
# what "Use this template" does not copy. Safe to run again.
#
#   1. labels          scripts/dev/labels.sh
#   2. merge settings  squash merges only, auto-merge allowed, merged branches deleted
#   3. ruleset         "Protect main": changes reach main only through a pull request whose
#                      check `build` is green; no force-push, no deleting main. The repository's
#                      admins may still merge a pull request over a red check (never push directly).
#
# What it cannot do, it prints at the end: the two secrets, the Claude GitHub app, the cloud
# environment and the three scheduled tasks.
#
# Usage: bash scripts/dev/setup-repo.sh [--dry-run]
#        (needs gh, signed in as an admin of the repository; --dry-run changes nothing)
set -euo pipefail
cd "$(dirname "$0")/../.."

dry=false
case "${1:-}" in
    "") ;;
    --dry-run) dry=true ;;
    *) echo "Usage: bash scripts/dev/setup-repo.sh [--dry-run]" >&2; exit 2 ;;
esac

repo="${GITHUB_REPOSITORY:-$(git remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##')}"
[[ "$repo" == */* ]] || { echo "Cannot tell which repository this is: set GITHUB_REPOSITORY=owner/name" >&2; exit 1; }
echo "Repository: $repo$($dry && echo '  (dry run: nothing is changed)')"

run() {
    if $dry; then
        printf '  would run:'; printf ' %q' "$@"; echo
    else
        "$@"
    fi
}

echo "1. Labels"
if $dry; then
    echo "  would run: GITHUB_REPOSITORY=$repo bash scripts/dev/labels.sh"
else
    GITHUB_REPOSITORY="$repo" bash scripts/dev/labels.sh >/dev/null
    echo "  done"
fi

echo "2. Merge settings"
run gh api -X PATCH "repos/$repo" --silent \
    -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
    -F allow_auto_merge=true -F delete_branch_on_merge=true
$dry || echo "  done"

echo "3. Ruleset \"Protect main\""
ruleset="$(mktemp)"
trap 'rm -f "$ruleset"' EXIT
cat > "$ruleset" <<'JSON'
{
  "name": "Protect main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [
    { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request" }
  ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      } },
    { "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [ { "context": "build" } ]
      } }
  ]
}
JSON
existing="$(gh api "repos/$repo/rulesets" --jq '.[] | select(.name == "Protect main") | .id' 2>/dev/null || true)"
if [[ -n "$existing" ]]; then
    run gh api -X PUT "repos/$repo/rulesets/$existing" --silent --input "$ruleset"
    $dry || echo "  updated"
elif $dry; then
    run gh api -X POST "repos/$repo/rulesets" --silent --input "$ruleset"
elif gh api -X POST "repos/$repo/rulesets" --silent --input "$ruleset"; then
    echo "  created"
else
    echo "  NOT created. On a private repository rulesets need a paid GitHub plan; otherwise" >&2
    echo "  check that you are an admin of $repo. Everything else above was set." >&2
fi

cat <<TEXT

Left to do by hand (SETUP-FROM-TEMPLATE.md has the details):
  a. Secret AUTOMERGE_TOKEN: a fine-grained token for $repo with Contents and Pull requests
     read/write (github.com/settings/personal-access-tokens), then:
       gh secret set AUTOMERGE_TOKEN --repo $repo
  b. Secret CLAUDE_CODE_OAUTH_TOKEN, only if this project keeps the review in CI:
       claude setup-token        # then paste its output into:
       gh secret set CLAUDE_CODE_OAUTH_TOKEN --repo $repo
  c. The Claude GitHub app has access to $repo: github.com/apps/claude
  d. The cloud environment: docs/cloud-environment.md
  e. The three scheduled tasks, then the four checks: docs/automation.md
TEXT
