package finances.backup;

import static org.assertj.core.api.Assertions.assertThat;

import finances.data.DataFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line: backup while the application runs, restore only when it is stopped. */
class BackupCommandTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> environment, String... args) {
        return BackupCommand.run(args, environment, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void knowsItsThreeCommandsOnly() {
        assertThat(BackupCommand.handles("backup")).isTrue();
        assertThat(BackupCommand.handles("restore")).isTrue();
        assertThat(BackupCommand.handles("undo-restore")).isTrue();
        assertThat(BackupCommand.handles("--spring.profiles.active=x")).isFalse();
    }

    @Test
    void aBackupWorksWhileTheApplicationHoldsTheDataFolderAndAllowsNoOverwrite() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        BackupFixtures.open(data).fill();
        Path target = Files.createDirectories(temp.resolve("other-disk"));

        try (DataFolder running = DataFolder.open(data.toString())) {
            assertThat(running).isNotNull();
            assertThat(run(Map.of("FINANCES_DATA_DIR", data.toString()), "backup", target.toString())).isZero();
        }
        try (var files = Files.list(target)) {
            assertThat(files.toList()).hasSize(1).allSatisfy(f -> assertThat(f.getFileName().toString()).matches("finances-backup-\\d{8}-\\d{6}\\.zip"));
        }
        Path explicit = target.resolve("mine.zip");
        assertThat(run(Map.of(), "backup", "--data-dir", data.toString(), explicit.toString())).isZero();
        assertThat(run(Map.of(), "backup", "--data-dir", data.toString(), explicit.toString())).isEqualTo(1);
        assertThat(said()).contains("already exists").doesNotContain("Corner Grocery").doesNotContain("NL51XMPL");
    }

    @Test
    void aRestoreAsksForYesThenReplacesAndUndoPutsTheOldOneBack() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        BackupFixtures live = BackupFixtures.open(data).fill();
        String atBackup = live.content();
        Path file = temp.resolve("backup.zip");
        assertThat(run(Map.of("FINANCES_DATA_DIR", data.toString()), "backup", file.toString())).isZero();
        live.accounts.addManual("Added later", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.CURRENT, "");
        String later = BackupFixtures.open(data).content();
        Map<String, String> env = Map.of("FINANCES_DATA_DIR", data.toString());

        assertThat(run(env, "restore", file.toString())).isEqualTo(2);
        assertThat(BackupFixtures.open(data).content()).isEqualTo(later);
        assertThat(said()).contains("It holds 4 accounts, 5 balances and 5 transactions").contains("will be replaced")
                .doesNotContain("Corner Grocery").doesNotContain("NL51XMPL");

        assertThat(run(env, "restore", file.toString(), "--yes")).isZero();
        assertThat(BackupFixtures.open(data).content()).isEqualTo(atBackup);
        assertThat(data.resolve("finances.previous.db")).exists();

        assertThat(run(env, "undo-restore")).isEqualTo(2);
        assertThat(run(env, "undo-restore", "--yes")).isZero();
        assertThat(BackupFixtures.open(data).content()).isEqualTo(later);
    }

    @Test
    void aRestoreIsRefusedWhileTheApplicationRunsAndWithABadFile() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        BackupFixtures.open(data).fill();
        Path file = temp.resolve("backup.zip");
        run(Map.of(), "backup", "--data-dir", data.toString(), file.toString());
        String before = BackupFixtures.open(data).content();

        try (DataFolder running = DataFolder.open(data.toString())) {
            assertThat(running).isNotNull();
            assertThat(run(Map.of(), "restore", "--data-dir", data.toString(), file.toString(), "--yes")).isEqualTo(1);
            assertThat(said()).contains("already in use");
        }
        Path bad = Files.writeString(temp.resolve("bad.zip"), "not a backup");
        assertThat(run(Map.of(), "restore", "--data-dir", data.toString(), bad.toString(), "--yes")).isEqualTo(1);
        assertThat(BackupFixtures.open(data).content()).isEqualTo(before);
    }

    @Test
    void wrongUseGivesTheUsageAndChangesNothing() {
        assertThat(run(Map.of(), "backup", "somewhere.zip")).isEqualTo(2);
        assertThat(said()).contains("Usage").contains("FINANCES_DATA_DIR");
        assertThat(run(Map.of("FINANCES_DATA_DIR", temp.toString()), "backup")).isEqualTo(2);
        assertThat(run(Map.of("FINANCES_DATA_DIR", temp.toString()), "undo-restore", "extra")).isEqualTo(2);
    }
}
