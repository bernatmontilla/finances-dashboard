package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import finances.data.DataFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session files and their tags (issue #39). Fictional ids only. */
class SessionFilesTest {

    @TempDir
    Path folder;

    DataFolder data;
    SessionFiles files;

    @BeforeEach
    void open() throws Exception {
        data = DataFolder.open(folder.resolve("data").toString());
        files = new SessionFiles(data);
    }

    @AfterEach
    void close() throws Exception {
        data.close();
    }

    private static Consent consent(long id, String tag) {
        return new Consent(id, "Example Bank", "NL", List.of(), Instant.parse("2026-11-26T12:00:00Z"),
                Instant.parse("2027-02-26T12:00:00Z"), ConsentStatus.AUTHORIZED, tag);
    }

    @Test
    void aFileWithTheTagOfTheConsentIsRead() {
        files.write(7, "session-one", "tag-one");

        assertThat(files.read(consent(7, "tag-one"))).contains("session-one");
    }

    @Test
    void aFileWithAnotherTagIsIgnored() {
        files.write(7, "session-of-another-consent", "tag-two");

        assertThat(files.read(consent(7, "tag-one"))).isEmpty();
    }

    @Test
    void aFileWithoutATagIsIgnoredByAConsentThatHasOneAndTheOtherWayRound() throws Exception {
        Files.createDirectories(files.fileOf(7).getParent());
        Files.writeString(files.fileOf(7), "leftover-session");
        assertThat(files.read(consent(7, "tag-one"))).as("a leftover file has no tag").isEmpty();

        files.write(8, "session-eight", "tag-eight");
        assertThat(files.read(consent(8, null))).as("a consent without a tag does not take a tagged file").isEmpty();
    }

    @Test
    void aConsentFromBeforeTagsKeepsItsUntaggedFile() throws Exception {
        Files.createDirectories(files.fileOf(7).getParent());
        Files.writeString(files.fileOf(7), "older-session");

        assertThat(files.read(consent(7, null))).contains("older-session");
    }

    @Test
    void aMissingFileIsEmptyAndDeletingRemovesTheFileAndItsTag() {
        assertThat(files.read(consent(7, "tag-one"))).isEmpty();
        files.write(7, "session-one", "tag-one");

        files.delete(7);

        assertThat(files.fileOf(7)).doesNotExist();
        assertThat(files.tagFileOf(7)).doesNotExist();
        assertThat(files.read(consent(7, "tag-one"))).isEmpty();
    }

    @Test
    void tagsAreRandom() {
        assertThat(SessionFiles.newTag()).hasSize(32).matches("[0-9a-f]+").isNotEqualTo(SessionFiles.newTag());
    }
}
