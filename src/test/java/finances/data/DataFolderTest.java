package finances.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataFolderTest {

    @TempDir
    Path temp;

    @Test
    void createsTheFolderOnFirstUse() {
        Path folder = temp.resolve("new").resolve("data");
        try (DataFolder opened = DataFolder.open(folder.toString())) {
            assertThat(Files.isDirectory(folder)).isTrue();
            assertThat(opened.databaseFile()).isEqualTo(folder.resolve("finances.db"));
        }
    }

    @Test
    void refusesAMissingSettingWithAClearMessage() {
        assertThatThrownBy(() -> DataFolder.open(null))
                .isInstanceOf(DataFolderException.class)
                .hasMessageContaining("finances.data-dir");
        assertThatThrownBy(() -> DataFolder.open("  "))
                .isInstanceOf(DataFolderException.class)
                .hasMessageContaining("finances.data-dir");
    }

    @Test
    void secondOpenOfTheSameFolderFailsCleanlyAndTheFolderCanBeReusedAfterClose() {
        DataFolder first = DataFolder.open(temp.toString());
        assertThatThrownBy(() -> DataFolder.open(temp.toString()))
                .isInstanceOf(DataFolderException.class)
                .hasMessageContaining("already in use");
        first.close();
        DataFolder.open(temp.toString()).close();
    }

    @Test
    void aRestoreCutOffBetweenTwoRenamesIsPutBackAtTheNextStart() throws Exception {
        Files.writeString(temp.resolve("finances.replaced.db"), "the owner's database");
        DataFolder.open(temp.toString()).close();
        assertThat(Files.readString(temp.resolve("finances.db"))).isEqualTo("the owner's database");
        assertThat(temp.resolve("finances.replaced.db")).doesNotExist();

        // With a database in place, the parked file is left alone for the owner to look at.
        Files.writeString(temp.resolve("finances.replaced.db"), "parked");
        DataFolder.open(temp.toString()).close();
        assertThat(Files.readString(temp.resolve("finances.db"))).isEqualTo("the owner's database");
        assertThat(Files.readString(temp.resolve("finances.replaced.db"))).isEqualTo("parked");
    }

    @Test
    void refusesAFileWhereAFolderIsExpected() throws Exception {
        Path file = Files.createFile(temp.resolve("not-a-folder"));
        assertThatThrownBy(() -> DataFolder.open(file.toString())).isInstanceOf(DataFolderException.class);
    }

    @Test
    void failureAnalyzerShowsTheMessageWithoutAStackTrace() {
        var analysis = new DataFolderFailureAnalyzer().analyze(new DataFolderException("The data folder is not set."));
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("The data folder is not set.");
        assertThat(analysis.getAction()).contains("finances.data-dir");
    }
}
