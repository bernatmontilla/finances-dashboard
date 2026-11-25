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

    private SpringApplicationBuilder app(String... properties) {
        return new SpringApplicationBuilder(FinancesApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties);
    }

    @Test
    void startingWithoutADataFolderFailsWithTheDataFolderMessage() {
        assertThatThrownBy(() -> app("finances.data-dir=").run())
                .hasRootCauseInstanceOf(DataFolderException.class)
                .rootCause()
                .hasMessageContaining("finances.data-dir");
    }

    @Test
    void startingTwiceOnTheSameDataFolderFailsTheSecondTime() {
        String dir = "finances.data-dir=" + temp;
        try (ConfigurableApplicationContext first = app(dir).run()) {
            assertThat(first.isActive()).isTrue();
            assertThatThrownBy(() -> app(dir).run())
                    .hasRootCauseInstanceOf(DataFolderException.class)
                    .rootCause()
                    .hasMessageContaining("already in use");
        }
    }
}
