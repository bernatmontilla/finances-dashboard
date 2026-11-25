package finances.backup;

import finances.data.DataFolder;
import java.time.Clock;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BackupConfig {

    @Bean
    BackupService backupService(DataFolder folder, GatedDataSource dataSource, BuildProperties build, Clock clock) {
        return new BackupService(folder.path(), dataSource.gate(), build.getVersion(), clock);
    }
}
