package finances.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.FinancesApplication;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class StartupFailureTest {

    @TempDir
    Path temp;

    /**
     * The data folder is passed as a command-line argument, which outranks an environment variable
     * FINANCES_DATA_DIR: a test must never open the folder of a real installation.
     */
    private ConfigurableApplicationContext run(String dataDir) {
        return new SpringApplicationBuilder(FinancesApplication.class)
                .web(WebApplicationType.NONE)
                .run("--finances.data-dir=" + dataDir);
    }

    @Test
    void startingWithoutADataFolderFailsWithTheDataFolderMessage() {
        assertThatThrownBy(() -> run(""))
                .hasRootCauseInstanceOf(DataFolderException.class)
                .rootCause()
                .hasMessageContaining("finances.data-dir");
    }

    @Test
    void startingTwiceOnTheSameDataFolderFailsTheSecondTime() {
        String dir = temp.toString();
        try (ConfigurableApplicationContext first = run(dir)) {
            assertThat(first.isActive()).isTrue();
            assertThatThrownBy(() -> run(dir))
                    .hasRootCauseInstanceOf(DataFolderException.class)
                    .rootCause()
                    .hasMessageContaining("already in use");
        }
    }
}
