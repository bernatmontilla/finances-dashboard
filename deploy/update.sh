#!/usr/bin/env bash
# Self-update of a finances-dashboard installation (docs/decisions/arch-deploy.md, docs/install.md).
#
# The machine pulls; nothing reaches into it. Asks GitHub for the latest release of this repository
# and, when it is newer than the installed one: downloads finances-dashboard.jar and its .sha256 to a
# temporary place, verifies the checksum, keeps the current jar as the previous one, swaps, restarts
# the service and polls /health until it reports the new version. If it does not, the previous jar
# is put back. The data folder is never touched: this script only works inside the app folder.
#
# Plain bash with curl and sha256sum (flock from util-linux guards against two runs at once).
# Never runs as root. Exit 0 when there is nothing to do or nothing could be checked (no network,
# rate limit): the timer tries again next day. Exit 1 when an update was refused or rolled back.
#
# Settings (environment; the defaults are right for an installation that follows docs/install.md):
#   FINANCES_APP_DIR         folder with finances-dashboard.jar          (default ~/finances-dashboard/app)
#   FINANCES_REPO            owner/name of the GitHub repository          (default below)
#   FINANCES_SERVICE         the systemd user service to restart          (default finances-dashboard.service)
#   SERVER_PORT              the port the app listens on                  (default 8080)
#   FINANCES_HEALTH_TIMEOUT  seconds to wait for the new version to answer (default 120)
# The remaining FINANCES_* settings exist so that scripts/dev/test-update.sh can run this script
# against a fake release directory and a fake health endpoint.
set -euo pipefail

APP_DIR="${FINANCES_APP_DIR:-$HOME/finances-dashboard/app}"
REPO="${FINANCES_REPO:-bernatmontilla/finances-dashboard}"
SERVICE="${FINANCES_SERVICE:-finances-dashboard.service}"
SYSTEMCTL="${FINANCES_SYSTEMCTL:-systemctl}"
API_URL="${FINANCES_RELEASE_API:-https://api.github.com/repos/$REPO/releases/latest}"
DOWNLOAD_BASE="${FINANCES_DOWNLOAD_BASE:-https://github.com/$REPO/releases/download}"
HEALTH_URL="${FINANCES_HEALTH_URL:-http://127.0.0.1:${SERVER_PORT:-8080}/health}"
HEALTH_TIMEOUT="${FINANCES_HEALTH_TIMEOUT:-120}"
HEALTH_INTERVAL="${FINANCES_HEALTH_INTERVAL:-3}"
CALLER_UID="${FINANCES_UID:-$(id -u)}"

JAR=finances-dashboard.jar
SUM=finances-dashboard.jar.sha256

log() { printf 'finances-dashboard-update: %s\n' "$*"; }

if [ "$CALLER_UID" -eq 0 ]; then
  log "refusing to run as root; run it as the user that owns the service"
  exit 1
fi

if [ ! -d "$APP_DIR" ]; then
  log "app folder $APP_DIR does not exist; see docs/install.md"
  exit 1
fi

# One run at a time; a second one simply leaves.
exec 9>"$APP_DIR/.update.lock"
if ! flock -n 9; then
  log "another update is running; nothing to do"
  exit 0
fi

curl_get() { curl --fail --silent --show-error --location --connect-timeout 20 --max-time 600 "$@"; }

work=
cleanup() { [ -z "$work" ] || rm -rf -- "$work"; }
trap cleanup EXIT

# --- what is the latest release? --------------------------------------------------------------
if ! release=$(curl_get --header 'Accept: application/vnd.github+json' "$API_URL" 2>/dev/null); then
  log "could not read the latest release (no network, GitHub unreachable or rate-limited); nothing changed"
  exit 0
fi
tag=$(printf '%s\n' "$release" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
if ! printf '%s' "$tag" | grep -Eq '^v?[0-9]+\.[0-9]+\.[0-9]+$'; then
  log "the latest release has no usable version tag; nothing changed"
  exit 0
fi
latest=${tag#v}

installed=
[ -f "$APP_DIR/installed-version" ] && installed=$(head -n 1 "$APP_DIR/installed-version")
if [ -n "$installed" ] && [ -f "$APP_DIR/$JAR" ]; then
  if [ "$installed" = "$latest" ]; then
    log "already on $installed; nothing to do"
    exit 0
  fi
  if [ "$(printf '%s\n%s\n' "$installed" "$latest" | sort -V | head -n 1)" != "$installed" ]; then
    log "installed $installed is newer than the latest release $latest; nothing to do"
    exit 0
  fi
fi

# --- download and verify, away from the running jar -------------------------------------------
work=$(mktemp -d "$APP_DIR/.update.XXXXXX")
log "downloading $latest"
if ! curl_get --output "$work/$JAR" "$DOWNLOAD_BASE/$tag/$JAR" ||
   ! curl_get --output "$work/$SUM" "$DOWNLOAD_BASE/$tag/$SUM"; then
  log "download failed; nothing changed"
  exit 0
fi
if ! (cd "$work" && sha256sum --check --status "$SUM"); then
  log "checksum of $latest does not match; nothing swapped"
  exit 1
fi

# --- swap, restart, check ---------------------------------------------------------------------
had_current=no
if [ -f "$APP_DIR/$JAR" ]; then
  had_current=yes
  cp -f -- "$APP_DIR/$JAR" "$APP_DIR/previous.jar"
  printf '%s\n' "$installed" > "$APP_DIR/previous-version"
fi
mv -f -- "$work/$JAR" "$APP_DIR/$JAR"
printf '%s\n' "$latest" > "$APP_DIR/installed-version"

restart() { "$SYSTEMCTL" --user restart "$SERVICE"; }

healthy() { # $1 = version that must be reported
  deadline=$((SECONDS + HEALTH_TIMEOUT))
  while :; do
    if body=$(curl --fail --silent --max-time 5 "$HEALTH_URL" 2>/dev/null) &&
       printf '%s' "$body" | grep -Fq '"status":"ok"' &&
       printf '%s' "$body" | grep -Fq "\"version\":\"$1\""; then
      return 0
    fi
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep "$HEALTH_INTERVAL"
  done
}

log "restarting $SERVICE on $latest"
if restart && healthy "$latest"; then
  log "now running $latest"
  exit 0
fi

log "$latest did not report healthy in ${HEALTH_TIMEOUT}s"
if [ "$had_current" = yes ]; then
  mv -f -- "$APP_DIR/previous.jar" "$APP_DIR/$JAR"
  if [ -n "$installed" ]; then
    printf '%s\n' "$installed" > "$APP_DIR/installed-version"
  else
    rm -f -- "$APP_DIR/installed-version"
  fi
  rm -f -- "$APP_DIR/previous-version"
  log "put the previous jar back, restarting"
  restart || log "restart of the previous jar failed too; check the service logs"
  if [ -n "$installed" ] && healthy "$installed"; then
    log "rolled back to $installed"
  else
    log "rolled back, but the previous jar is not confirmed healthy; check the service logs"
  fi
else
  log "there was no previous jar to put back; check the service logs"
fi
exit 1
