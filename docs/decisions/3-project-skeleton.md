# #3 Project skeleton

- **Spring Boot 4.0.x, Java 25, Maven wrapper 3.9.11.** Boot 4 splits some starters: Flyway
  comes with `spring-boot-starter-flyway` (the approved "Flyway"); `spring-boot-starter-web` is still
  the web starter. `sqlite-jdbc` is pinned in the pom because the Boot BOM does not manage it.
- **Package `finances.data`** (not in the layout of `arch-stack.md`): the data folder, its lock
  and the SQLite `DataSource`. It is infrastructure that every domain package needs, and it fits
  neither `web` nor `backup`. Add it to the layout when the lead next edits that file.
- **The data folder has no default.** `finances.data-dir` is read in `DataConfig`; empty means
  `DataFolderException`, which a `FailureAnalyzer` prints as a short message with no stack trace.
  A guard test fails when a properties file in `src/` fixes a value for it.
- **Second start on the same folder** fails because `DataFolder` holds an exclusive
  `FileLock` on `finances.lock` for as long as the application runs. The lock is released by the
  OS when the process dies, so a crash never leaves the folder blocked.
- **Database**: `finances.db` in the data folder, WAL mode, busy timeout 5 s, foreign keys on.
  Migration `V1__installation.sql` creates one table with one row (creation time, UTC). Merged
  migrations are never edited.
- **`/health`** answers 200 `{"status":"ok","version":…}` or 503 `{"status":"unavailable","version":…}`;
  the body never carries an error message. "Reachable" means the migrated schema answers (`select 1 from installation where id = 1`), so a missing, empty or corrupt database file gives 503, not 200. The contract is the status code and the field names (`status`, `version`), not the byte layout. The version is the Maven version (`BuildProperties`).
  This is a public interface now (hard rule 4).
- **Templates**: an element that shows text has an empty body and `th:text="#{key}"`; the guard test
  relies on that to find literal texts. The `xmlns:th` declaration is left out because it is a URL and
  the guard against other hosts would flag it; Thymeleaf does not need it.
- **Version** is `0.1.0-SNAPSHOT` in `pom.xml`; releases and version bumps are a later issue.
- **Build**: `scripts/dev/build.sh` is `./mvnw verify`; the jar is `target/finances-dashboard.jar`
  (`finalName`). CI sets up Temurin 25. Full build locally: about 20 s.
- **Tests and the environment**: `StartupFailureTest` passes the folder as a command-line argument, which
  outranks `FINANCES_DATA_DIR`, so a test never opens the folder of a real installation.
- **For the lead** (`.github/` is out of scope here, hard rule 5): consider adding
  `src/*/java/finances/data/**` to `risk:high` in `labeler.yml`, because that package decides where the
  owner's data lives. The JSON body of `/health` (`status`, `version`) is part of its contract.
- **Maven wrapper** pins `distributionSha256Sum` of Maven 3.9.11 (checked against the SHA-512 Maven Central publishes).
