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
# FAKE_STRICT=1 imitates a service in systemd's start limit: restart is refused until reset-failed ran.
if [ "$2" = reset-failed ]; then touch "$FAKE_ROOT/reset"; fi
if [ "$2" = restart ] && [ "${FAKE_STRICT:-}" = 1 ]; then
  [ -e "$FAKE_ROOT/reset" ] || { rm -f "$FAKE_ROOT/health.json"; exit 1; }
  rm -f "$FAKE_ROOT/reset"
fi
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
  rm -rf "$root/app" "$root/data" "$root/systemctl.log" "$root/health.json" "$root/releases" "$root/latest.json" "$root/reset"
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
  FINANCES_REPO=owner/name \
  FINANCES_RELEASE_API="file://$root/latest.json" \
  FINANCES_DOWNLOAD_BASE="file://$root/releases" \
  FINANCES_HEALTH_URL="file://$root/health.json" \
  FINANCES_HEALTH_TIMEOUT=2 FINANCES_HEALTH_INTERVAL=1 \
  FINANCES_UID=1000 \
  FINANCES_CURL_PROTO==file \
  FINANCES_TEST=1 \
  "$@" "$BASH" "$UPDATE" >"$root/out.log" 2>&1
}

restarts() { grep -c ' restart ' "$root/systemctl.log" || true; }
# A bin folder with the tools update.sh needs, except the ones named: for "a tool is missing" cases.
path_without() {
  rm -rf "$root/bin"; mkdir -p "$root/bin"
  local t
  for t in awk cat cmp cp curl cut dirname find flock grep head id mkdir mktemp mv rm sed sha256sum sleep sort tr; do
    case " $* " in *" $t "*) continue ;; esac
    ln -s "$(command -v "$t")" "$root/bin/$t"
  done
}
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

# 4b. A checksum file that names another file, or is empty, does not vouch for the download.
new_installation 1.1.0; publish v1.2.0 1.2.0
(cd "$root/releases/v1.2.0" && printf '%s  ../finances-dashboard.jar\n' "$(sha256sum < /dev/null | cut -d' ' -f1)" > finances-dashboard.jar.sha256)
if run_update env; then fail "checksum of another file: update.sh must exit non-zero"; fi
: > "$root/releases/v1.2.0/finances-dashboard.jar.sha256"
if run_update env; then fail "empty checksum file: update.sh must exit non-zero"; fi
check "checksum of another file: nothing swapped" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

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
check "rollback: nothing is left pending" test ! -e "$root/app/pending-version"
# The same broken release is not tried again every day, but a newer one is.
: > "$root/systemctl.log"
run_update env || fail "rollback: a second run must leave the failed release alone"
check "rollback: the failed release is skipped" test "$(restarts)" = 0 -a "$(jar_version)" = 1.1.0
publish v1.3.0 1.3.0
run_update env || fail "rollback: a newer release must install: $(cat "$root/out.log")"
check "rollback: a newer release is installed" test "$(jar_version)" = 1.3.0 -a "$(cat "$root/app/installed-version")" = 1.3.0
check "rollback: the failure mark is cleared" test ! -e "$root/app/failed-version"

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

# 10b. A run cut off between the swap and the health check leaves a marker, which the next run settles.
new_installation 1.1.0
printf '1.2.0\n' > "$root/app/finances-dashboard.jar"; printf '1.1.0\n' > "$root/app/previous.jar"
printf '1.2.0\n' > "$root/app/pending-version"; printf '1.1.0\n' > "$root/app/previous-version"
publish v1.2.0 1.2.0; printf '{"status":"ok","version":"1.2.0"}' > "$root/health.json"
run_update env || fail "pending healthy: update.sh failed: $(cat "$root/out.log")"
check "pending healthy: recorded as installed" test "$(cat "$root/app/installed-version")" = 1.2.0 -a ! -e "$root/app/pending-version"
check "pending healthy: no restart" test "$(restarts)" = 0
new_installation 1.1.0
printf '1.2.0 bad\n' > "$root/app/finances-dashboard.jar"; printf '1.1.0\n' > "$root/app/previous.jar"
printf '1.2.0\n' > "$root/app/pending-version"; printf '1.1.0\n' > "$root/app/previous-version"
publish v1.2.0 "1.2.0 bad"; rm -f "$root/health.json"
if run_update env; then fail "pending unhealthy: update.sh must exit non-zero"; fi
check "pending unhealthy: the previous jar is back" test "$(jar_version)" = 1.1.0 -a "$(cat "$root/app/installed-version")" = 1.1.0
check "pending unhealthy: marker cleared and release remembered as failed" test ! -e "$root/app/pending-version" -a "$(cat "$root/app/failed-version")" = 1.2.0

# 10c. FINANCES_REPO is required: no owner name is built into the script.
new_installation 1.1.0; publish v1.2.0 1.2.0
if run_update env FINANCES_REPO= FINANCES_RELEASE_API=; then fail "no repository: update.sh must refuse"; fi
check "no repository: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 11. Never as root.
new_installation 1.1.0; publish v1.2.0 1.2.0
if run_update env FINANCES_UID=0; then fail "root: update.sh must refuse"; fi
check "root: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0

# 12. A rollback after a crash loop: systemd refuses restart (start limit) until reset-failed ran,
# so every restart the script makes is preceded by one.
new_installation 1.1.0; publish v1.2.0 "1.2.0 bad"
if run_update env FAKE_STRICT=1; then fail "start limit: update.sh must exit non-zero"; fi
check "start limit: the previous jar is back" test "$(jar_version)" = 1.1.0
check "start limit: the previous jar was restarted and reports healthy" grep -q '"version":"1.1.0"' "$root/health.json"
check "start limit: reset-failed ran before each restart" test "$(grep -c ' reset-failed ' "$root/systemctl.log")" = 2

# 13. A run cut off after pending-version was written, before the swap: jar and previous jar are the
# same file, the service never ran the pending version. No rollback, no failure mark; the update runs.
new_installation 1.1.0
cp "$root/app/finances-dashboard.jar" "$root/app/previous.jar"
printf '1.2.0\n' > "$root/app/pending-version"; printf '1.1.0\n' > "$root/app/previous-version"
publish v1.2.0 1.2.0
run_update env || fail "cut off before the swap: update.sh failed: $(cat "$root/out.log")"
check "cut off before the swap: not marked as failed" test ! -e "$root/app/failed-version"
check "cut off before the swap: the release was installed" test "$(jar_version)" = 1.2.0 -a "$(cat "$root/app/installed-version")" = 1.2.0
check "cut off before the swap: nothing left pending" test ! -e "$root/app/pending-version"

# 14. A run cut off after the swap, before the restart: the service still runs the old jar. The next
# run restarts it once, and then the pending version reports healthy.
new_installation 1.1.0
printf '1.2.0\n' > "$root/app/finances-dashboard.jar"; printf '1.1.0\n' > "$root/app/previous.jar"
printf '1.2.0\n' > "$root/app/pending-version"; printf '1.1.0\n' > "$root/app/previous-version"
publish v1.2.0 1.2.0
run_update env || fail "cut off before the restart: update.sh failed: $(cat "$root/out.log")"
check "cut off before the restart: recorded as installed" test "$(cat "$root/app/installed-version")" = 1.2.0 -a ! -e "$root/app/pending-version"
check "cut off before the restart: restarted once" test "$(restarts)" = 1
check "cut off before the restart: not marked as failed" test ! -e "$root/app/failed-version"

# 14b. A first installation cut off after the marker, before the swap: no jar at all. Same: no failure mark.
new_installation; publish v1.2.0 1.2.0
printf '1.2.0\n' > "$root/app/pending-version"
run_update env || fail "first install cut off: update.sh failed: $(cat "$root/out.log")"
check "first install cut off: not marked as failed" test ! -e "$root/app/failed-version"
check "first install cut off: the release was installed" test "$(jar_version)" = 1.2.0

# 15. A missing tool is reported, with exit 1, not mistaken for "another update is running".
for tool in flock curl sha256sum; do
  new_installation 1.1.0; publish v1.2.0 1.2.0
  path_without "$tool"
  if run_update env PATH="$root/bin"; then fail "no $tool: update.sh must exit non-zero"; fi
  check "no $tool: the message names it" grep -q "$tool" "$root/out.log"
  check "no $tool: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0
done

# 16. The test-only switches do nothing without FINANCES_TEST=1: a file:// source is refused, so
# nothing is fetched, nothing swapped, and each ignored switch is named.
new_installation 1.1.0; publish v1.2.0 1.2.0
run_update env -u FINANCES_TEST || true
check "no test mode: nothing changed" test "$(jar_version)" = 1.1.0 -a "$(restarts)" = 0
for var in FINANCES_CURL_PROTO FINANCES_UID FINANCES_SYSTEMCTL; do
  check "no test mode: $var is named as ignored" grep -q "ignoring $var" "$root/out.log"
done

if [ "$failures" -ne 0 ]; then
  printf '%s check(s) of deploy/update.sh failed\n' "$failures"
  exit 1
fi
echo "deploy/update.sh: all checks passed"
