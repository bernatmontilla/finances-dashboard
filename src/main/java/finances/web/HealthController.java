package finances.web;

import java.sql.Connection;
import java.util.Map;
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
    ResponseEntity<Map<String, String>> health() {
        if (databaseReachable()) {
            return ResponseEntity.ok(Map.of("status", "ok", "version", build.getVersion()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable", "version", build.getVersion()));
    }

    private boolean databaseReachable() {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("select 1")) {
            return result.next();
        } catch (Exception e) {
            return false;
        }
    }
}
