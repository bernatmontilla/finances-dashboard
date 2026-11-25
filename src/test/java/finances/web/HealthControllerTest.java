package finances.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;

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
        assertThat(response.getBody()).containsEntry("status", "unavailable").doesNotContainValue("unreachable");
    }
}
