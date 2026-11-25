package finances.data;

import finances.backup.DatabaseGate;
import finances.backup.GatedDataSource;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Held by every open connection; a restore waits until none is open (docs/decisions/12-backup-and-restore.md). */
    @Bean
    DatabaseGate databaseGate() {
        return new DatabaseGate();
    }

    /**
     * Uploads (a backup file to restore) are written into the data folder's own scratch folder, not
     * the system's temporary folder, and are not limited in size: a backup grows with the data.
     */
    @Bean
    MultipartConfigElement multipartConfigElement(DataFolder dataFolder) throws IOException {
        Path scratch = dataFolder.path().resolve(DataFolder.SCRATCH_FOLDER);
        Files.createDirectories(scratch);
        return new MultipartConfigElement(scratch.toString(), -1L, -1L, 0);
    }

    @Bean
    GatedDataSource dataSource(DataFolder dataFolder, DatabaseGate gate) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dataFolder.databaseFile());
        return new GatedDataSource(dataSource, gate);
    }
}
