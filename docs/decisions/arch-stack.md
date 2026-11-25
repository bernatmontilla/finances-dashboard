# Architecture: stack

Decided in planning round 1.

- **Java 25, Spring Boot, one executable jar.** A mature, well-known stack that runs anywhere a
  JDK does and needs nothing else installed. The option not taken: Go or Python with SQLite, about
  a third of the memory, but less familiar tooling for this project's maintainers.
- **Maven with the wrapper** (`./mvnw`). Gradle would do as well; nothing else depends on it.
- **SQLite, one file in the data folder**, schema by Flyway migrations. One user, a small
  low-memory machine as the target, and backup must be one file. A migration is never edited
  once merged. Not PostgreSQL: more memory and one more thing for others to install.
- **Server-rendered pages**: Thymeleaf, a little vanilla JavaScript, charts as inline SVG drawn on
  the server. No Node build, no front-end framework, no CDN: every asset is served by the app
  itself (PRODUCT invariant 2).
- **Memory**: the service runs with a heap cap of about 300 MB. Anything that loads all
  transactions into memory at once is a bug.
- **Money** is a value type of `long` cents plus currency; `double`, `float` and `BigDecimal`
  arithmetic on amounts are not used. Times are stored in UTC; booking dates are plain dates.
- **Outbound HTTP goes through one package** (`finances.outbound`), which refuses any host that is
  not configured. A guard test fails when another package opens a connection.
- **No authentication in the app.** It binds to `127.0.0.1`; the installer's private network publishes it.

Approved dependencies (hard rule 2): Spring Boot starters `web`, `thymeleaf`, `jdbc`, `validation`,
`test`; `org.xerial:sqlite-jdbc`; Flyway. JWT signing for Enable Banking uses the JDK, no library.
Anything else needs the owner's approval in the issue.

Package layout under `src/main/java/finances/`: `money` (amounts, the headline numbers),
`accounts`, `sync` (bank), `importing` (CSV), `outbound`, `backup`, `web`. Domain rules live in
the domain packages, never in controllers or templates.
