package finances.web;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public interface (AGENTS.md hard rule 4): 200 with the version when the database answers,
 * 503 otherwise. The body never carries an error message.
 */
@RestController
class HealthController {

    private final DataSource dataSource;
    private final BuildProperties build;

    HealthController(DataSource dataSource, BuildProperties build) {
        this.dataSource = dataSource;
        this.build = build;
    }

    @GetMapping("/health")
    ResponseEntity<Health> health() {
        if (databaseReachable()) {
            return ResponseEntity.ok(new Health("ok", build.getVersion()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Health("unavailable", build.getVersion()));
    }

    /** Field order is fixed by the record: status, then version. */
    record Health(String status, String version) {
    }

    /** Reads the migrated schema, so a missing, empty or corrupt database file is not "reachable". */
    private boolean databaseReachable() {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("select 1 from installation where id = 1")) {
            return result.next();
        } catch (Exception e) {
            return false;
        }
    }
}
