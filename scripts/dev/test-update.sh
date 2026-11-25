#!/usr/bin/env bash
# Exercises deploy/update.sh against a fake release directory, a fake systemctl and a fake health
# endpoint (all plain files, no network, no systemd, nothing outside a temporary folder).
# Run by scripts/dev/build.sh, so CI runs it. Fictional versions only.
set -euo pipefail
cd "$(dirname "$0")/../.."
UPDATE="$PWD/deploy/update.sh"

root=$(mktemp -d)
trap 'rm -rf -- "$root"' EXIT
failures=0

fail() { printf 'FAIL: %s\n' "$*"; failures=$((failures + 1)); }
check() { # description, then a command that must succeed
  local what=$1; shift
  "$@" >/dev/null 2>&1 || fail "$what"
}

# A fake "jar" is a text file; its first line says which version it is, and "bad" in it makes the
# fake service never report healthy. The fake systemctl logs each call and, on restart, writes what
# the real app's /health would say for the jar that is installed.
cat > "$root/systemctl" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_ROOT/systemctl.log"
if [ "$2" = restart ]; then
  jar="$FINANCES_APP_DIR/finances-dashboard.jar"
  if grep -q bad "$jar"; then
    rm -f "$FAKE_ROOT/health.json"
  else
    printf '{"status":"ok","version":"%s"}' "$(head -n 1 "$jar")" > "$FAKE_ROOT/health.json"
  fi
fi
FAKE
chmod +x "$root/systemctl"

publish() { # tag, jar content ("1.2.0" or "1.2.0 bad"), [wrong checksum]
  local tag=$1 content=$2 dir="$root/releases/$1"
  mkdir -p "$dir"
  printf '%s\n' "$content" > "$dir/finances-dashboard.jar"
  (cd "$dir" && sha256sum finances-dashboard.jar > finances-dashboard.jar.sha256)
  [ "${3:-}" = wrong ] && printf '%064d  finances-dashboard.jar\n' 0 > "$dir/finances-dashboard.jar.sha256"
  printf '{\n  "tag_name": "%s",\n  "name": "%s"\n}\n' "$tag" "$tag" > "$root/latest.json"
  return 0
}

new_installation() { # [installed version]; the jar's first line is its version
  rm -rf "$root/app" "$root/data" "$root/systemctl.log" "$root/health.json" "$root/releases" "$root/latest.json"
  mkdir -p "$root/app" "$root/data"
  printf 'owner entered this\n' > "$root/data/finances.db"
  if [ -n "${1:-}" ]; then
    printf '%s\n' "$1" > "$root/app/finances-dashboard.jar"
    printf '%s\n' "$1" > "$root/app/installed-version"
    printf '{"status":"ok","version":"%s"}' "$1" > "$root/health.json"
  fi
  : > "$root/systemctl.log"
  data_before=$(cd "$root/data" && sha256sum -- * | sha256sum)
}

run_update() { # extra environment can be put in front by the caller via env
  FAKE_ROOT="$root" \
  FINANCES_APP_DIR="$root/app" \
  FINANCES_SYSTEMCTL="$root/systemctl" \
  FINANCES_RELEASE_API="file://$root/latest.json" \
  FINANCES_DOWNLOAD_BASE="file://$root/releases" \
  FINANCES_HEALTH_URL="file://$root/health.json" \
  FINANCES_HEALTH_TIMEOUT=2 FINANCES_HEALTH_INTERVAL=1 \
  FINANCES_UID=1000 \
  "$@" bash "$UPDATE" >"$root/out.log" 2>&1
}

restarts() { grep -c ' restart ' "$root/systemctl.log" || true; }
jar_version() { head -n 1 "$root/app/finances-dashboard.jar"; }
data_untouched() { [ "$(cd "$root/data" && sha256sum -- * | sha256sum)" = "$data_before" ]; }
no_leftovers() { [ -z "$(find "$root/app" -maxdepth 1 -name '.update.*' ! -name '.update.lock')" ]; }

# 1. A newer version is installed, the old jar kept as the previous one, the service restarted once.
new_installation 1.1.0; publish v1.2.0 1.2.0
run_update env || fail "newer: update.sh failed: $(cat "$root/out.log")"
check "newer: the new jar is installed" test "$(jar_version)" = 1.2.0
check "newer: the version is recorded" test "$(cat "$root/app/installed-version")" = 1.2.0
check "newer: the previous jar is kept" test "$(head -n 1 "$root/app/previous.jar")" = 1.1.0
check "newer: one restart" test "$(restarts)" = 1
check "newer: the data folder is untouched" data_untouched
check "newer: no temporary files left" no_leftovers

# 2. The same version: nothing happens, no restart.
new_installation 1.2.0; publish v1.2.0 1.2.0
run_update env || fail "same: update.sh failed: $(cat "$root/out.log")"
check "same: no restart" test "$(restarts)" = 0
check "same: no previous jar made" test ! -e "$root/app/previous.jar"
check "same: the jar is unchanged" test "$(jar_version)" = 1.2.0

# 3. A release older than the installed one is not a downgrade.
new_installation 1.3.0; publish v1.2.0 1.2.0
run_update env || fail "older: update.sh failed"
check "older: nothing changed" test "$(jar_version)" = 1.3.0 -a "$(restarts)" = 0

# 4. A bad checksum: nothing is swapped, nothing restarted, the run is reported as failed.
new_installation 1.1.0; publish v1.2.0 1.2.0 wrong
if run_update env; then fail "checksum: update.sh must exit non-zero"; fi
check "checksum: the old jar stays" test "$(jar_version)" = 1.1.0
check "checksum: the old version stays" test "$(cat "$root/app/installed-version")" = 1.1.0
check "checksum: no restart" test "$(restarts)" = 0
check "checksum: no previous jar made" test ! -e "$root/app/previous.jar"
check "checksum: no temporary files left" no_leftovers

# 5. A partial download (the checksum file is missing): nothing is swapped.
new_installation 1.1.0; publish v1.2.0 1.2.0; rm "$root/releases/v1.2.0/finances-dashboard.jar.sha256"
run_update env || fail "partial: update.sh failed: $(cat "$root/out.log")"
check "partial: the old jar stays" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 6. The new version never becomes healthy: the previous jar is put back and restarted.
new_installation 1.1.0; publish v1.2.0 "1.2.0 bad"
if run_update env; then fail "rollback: update.sh must exit non-zero"; fi
check "rollback: the previous jar is back" test "$(jar_version)" = 1.1.0
check "rollback: the previous version is recorded again" test "$(cat "$root/app/installed-version")" = 1.1.0
check "rollback: restarted for the new jar and again for the old one" test "$(restarts)" = 2
check "rollback: the old version reports healthy" grep -q '"version":"1.1.0"' "$root/health.json"
check "rollback: the data folder is untouched" data_untouched
check "rollback: no temporary files left" no_leftovers

# 7. No network or GitHub unreachable or rate-limited: exit 0, nothing changed.
new_installation 1.1.0; publish v1.2.0 1.2.0; rm "$root/latest.json"
run_update env || fail "offline: update.sh must exit 0: $(cat "$root/out.log")"
check "offline: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 8. A release whose tag is not a version is ignored (the tag ends up in paths and URLs).
new_installation 1.1.0; publish 'v1.2.0/../../x' 1.2.0
run_update env || fail "odd tag: update.sh must exit 0"
check "odd tag: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 9. First installation: no jar yet; it is installed and the service started.
new_installation; publish v1.2.0 1.2.0
run_update env || fail "first install: update.sh failed: $(cat "$root/out.log")"
check "first install: the jar is installed" test "$(jar_version)" = 1.2.0
check "first install: one restart" test "$(restarts)" = 1
check "first install: no previous jar" test ! -e "$root/app/previous.jar"

# 10. Two runs at once: the second one leaves without changing anything.
new_installation 1.1.0; publish v1.2.0 1.2.0
( exec 9>"$root/app/.update.lock"; flock -n 9; run_update env ) || fail "lock: update.sh must exit 0 while another run holds the lock"
check "lock: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 11. Never as root.
new_installation 1.1.0; publish v1.2.0 1.2.0
if run_update env FINANCES_UID=0; then fail "root: update.sh must refuse"; fi
check "root: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) of deploy/update.sh failed\n' "$failures"
  exit 1
fi
echo "deploy/update.sh: all checks passed"
