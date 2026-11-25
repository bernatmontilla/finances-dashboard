package finances.data;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The data folder and the SQLite database in it. There is no default folder: it must be set. */
@Configuration(proxyBeanMethods = false)
public class DataConfig {

    @Bean(destroyMethod = "close")
    DataFolder dataFolder(Environment environment) {
        return DataFolder.open(environment.getProperty("finances.data-dir"));
    }

    @Bean
    DataSource dataSource(DataFolder dataFolder) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dataFolder.databaseFile());
        return dataSource;
    }
}
