#!/usr/bin/env bash
# The one build-and-test command, used by humans, agents (AGENTS.md) and CI (.github/workflows/ci.yml).
# Runs every test and produces target/finances-dashboard.jar. Needs a JDK 25 (JAVA_HOME or on PATH).
set -euo pipefail
cd "$(dirname "$0")/../.."

bash scripts/dev/test-update.sh

./mvnw -B -ntp verify

test -f target/finances-dashboard.jar
echo "Built target/finances-dashboard.jar"
