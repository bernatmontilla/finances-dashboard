# Architecture: release and deploy

Decided in planning round 1.

- **CI on GitHub's own runners builds the jar; a merge to `main` publishes a GitHub Release** with
  `finances-dashboard.jar` and `finances-dashboard.jar.sha256`. The version comes from the
  `release:*` label of the merged PR. These two asset names are a public interface (hard rule 4).
- **The machine pulls; GitHub never reaches in.** A systemd user timer runs `deploy/update.sh`,
  which asks for the latest release, downloads it, verifies the checksum, swaps the jar, restarts
  the service and checks its health endpoint. If any step fails the old jar keeps running (or is
  put back).
- **No self-hosted runner.** The repository is public: a runner on a private machine would let a
  pull request from a fork run code there. `RUNS_ON` stays unset.
- **The repository holds templates only**: `deploy/*.service`, `deploy/*.timer`, `deploy/update.sh`
  and `docs/install.md`, with placeholders (`<host>`, `~`, a port). No installation's real unit files,
  port, host name or data folder are ever committed (hard rule 1).
- **A systemd user service**, memory-capped, bound to `127.0.0.1`. Publishing it on a private
  network (for example `tailscale serve`) is the installer's step and is described, not scripted.
- **Not Docker**: one jar and a JDK is the smallest footprint on a low-memory machine. A
  Dockerfile can be added in a later round; it is a non-goal now.
- Known limit: the update check trusts GitHub Releases of this repository and the checksum file
  published next to the jar. That protects against a broken download, not against a compromised
  repository; `main` is protected and risky paths need the owner's merge.
