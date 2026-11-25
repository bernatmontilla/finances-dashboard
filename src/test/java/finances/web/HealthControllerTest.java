package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.sqlite.SQLiteDataSource;

class HealthControllerTest {

    private final BuildProperties build = new BuildProperties(propertiesWithVersion("1.2.3"));

    private static Properties propertiesWithVersion(String version) {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        return properties;
    }

    @Test
    void answers503WhenTheDatabaseCannotBeReached() {
        DataSource broken = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override
            public java.sql.Connection getConnection() throws SQLException {
                throw new SQLException("unreachable");
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("unreachable");
            }
        };
        var response = new HealthController(broken, build).health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new HealthController.Health("unavailable", "1.2.3"));
    }

    @Test
    void answers503ForADatabaseFileWithoutTheMigratedSchemaAndOkOnceItHasIt(@TempDir Path temp) throws Exception {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("finances.db"));
        var controller = new HealthController(dataSource, build);

        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create table installation (id integer primary key, created_at text not null)");
            statement.execute("insert into installation values (1, '2026-01-01T00:00:00Z')");
        }
        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.health().getBody()).isEqualTo(new HealthController.Health("ok", "1.2.3"));

        Files.delete(temp.resolve("finances.db"));
        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
