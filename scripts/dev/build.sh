#!/usr/bin/env bash
# The one build-and-test command, used by humans, agents (AGENTS.md) and CI (.github/workflows/ci.yml).
# Replace the body with this project's build, for example:
#   ./gradlew test bootJar
#   npm ci && npm test && npm run build
#   uv run pytest
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "::warning::No build defined yet: fill in scripts/dev/build.sh"
echo "No build defined yet: fill in scripts/dev/build.sh"
