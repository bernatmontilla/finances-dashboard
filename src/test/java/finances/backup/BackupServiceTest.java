package finances.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.backup.BackupException.Reason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Backup and restore on real SQLite files, without the web layer. Fictional data only. */
class BackupServiceTest {

    @TempDir
    Path temp;

    private Path folder(String name) throws IOException {
        return Files.createDirectories(temp.resolve(name));
    }

    private static BackupService service(Path folder) {
        return new BackupService(folder, new DatabaseGate(), "1.2.3", BackupFixtures.CLOCK);
    }

    private static Path backup(Path folder, Path file) {
        return service(folder).backupTo(file);
    }

    private static String content(Path folder) {
        return BackupFixtures.open(folder).content();
    }

    private static void assertNothingStaged(Path folder) throws IOException {
        Path scratch = folder.resolve("tmp");
        if (Files.isDirectory(scratch)) {
            try (Stream<Path> files = Files.list(scratch)) {
                assertThat(files.map(p -> p.getFileName().toString()).toList()).as("scratch folder is empty").isEmpty();
            }
        }
    }

    // ----- the round trip

    @Test
    void backupWipeRestoreBringsEverythingBackIncludingTheNote() throws Exception {
        Path folder = folder("data");
        BackupFixtures original = BackupFixtures.open(folder).fill();
        String before = original.content();
        assertThat(before).contains(BackupFixtures.NOTE).contains("Corner Grocery").contains("NL51XMPL0000000001");
        Path file = backup(folder, temp.resolve("backup.zip"));
        assertThat(file).exists();

        BackupFixtures.wipe(folder);
        assertThat(folder.resolve("finances.db")).doesNotExist();

        BackupService service = service(folder);
        RestorePlan plan = service.stage(file);
        assertThat(plan.backup()).isEqualTo(new Summary(4, 5, 5));
        assertThat(plan.current()).isEqualTo(Summary.EMPTY);
        service.restore(plan.token(), "restore");

        assertThat(content(folder)).isEqualTo(before);
        assertThat(BackupFixtures.open(folder).transactions.all(0).items()).anySatisfy(t -> assertThat(t.note()).isEqualTo(BackupFixtures.NOTE));
        assertNothingStaged(folder);
        assertThat(folder.resolve("finances.previous.db")).doesNotExist();
        assertThat(Files.list(folder).map(p -> p.getFileName().toString()).filter(n -> n.endsWith("-wal") || n.endsWith("-shm")).toList())
                .isEmpty();
    }

    @Test
    void restoringTheBackupThatWasJustMadeChangesNothingVisibleAndDuplicatesNothing() throws Exception {
        Path folder = folder("data");
        BackupFixtures live = BackupFixtures.open(folder).fill();
        String before = live.content();
        Path file = backup(folder, temp.resolve("backup.zip"));

        BackupService service = service(folder);
        RestorePlan plan = service.stage(file);
        assertThat(plan.current()).isEqualTo(plan.backup());
        service.restore(plan.token(), "RESTORE");

        BackupFixtures after = BackupFixtures.open(folder);
        assertThat(after.content()).isEqualTo(before);
        assertThat(after.count("transactions")).isEqualTo(5);
        // Delivering the same transactions again still adds nothing.
        assertThat(after.transactions.deliver(1, finances.accounts.Source.CSV, List.of(
                BackupFixtures.booked(java.time.LocalDate.of(2026, 6, 3), -1234, "Corner Grocery", "Groceries", "REF-1"))).added()).isZero();
    }

    @Test
    void theBackupHoldsAManifestAndNoSecretFile() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Files.writeString(folder.resolve("enablebanking.key"), "not-a-real-key");
        Path file = backup(folder, temp.resolve("backup.zip"));

        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactly("manifest.properties", "finances.db");
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.properties")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(manifest).contains("format=finances-backup").contains("formatVersion=1").contains("applicationVersion=1.2.3")
                    .contains("schemaVersion=3").contains("createdAt=2026-06-15T10\\:00\\:00Z").contains("databaseSha256=");
        }
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).as("no secret in the file").doesNotContain("not-a-real-key");
        assertNothingStaged(folder);
    }

    @Test
    void aBackupOfAnOlderDatabaseRestoresAndIsBroughtUpToDate() throws Exception {
        Path old = folder("old");
        BackupFixtures older = BackupFixtures.openOlder(old, "2");
        older.accounts.addManual("Everyday", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.CURRENT, "NL51XMPL0000000001");
        Path file = backup(old, temp.resolve("old.zip"));

        Path fresh = folder("fresh");
        BackupService service = service(fresh);
        RestorePlan plan = service.stage(file);
        assertThat(plan.manifest().schemaVersion()).isEqualTo("2");
        service.restore(plan.token(), "RESTORE");

        BackupFixtures restored = BackupFixtures.open(fresh);
        assertThat(restored.count("account")).isEqualTo(1);
        assertThat(restored.count("transactions")).isZero(); // the table exists: V3 was applied by the restore
    }

    @Test
    void aBackupWhileSomethingIsWritingIsConsistent() throws Exception {
        Path folder = folder("data");
        BackupFixtures live = BackupFixtures.open(folder).fill();
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> writerFailures = new ArrayList<>();
        Thread writer = new Thread(() -> {
            try (Connection connection = live.dataSource.getConnection()) {
                connection.setAutoCommit(false);
                int i = 0;
                while (!stop.get()) {
                    // Two rows that belong together, in one transaction: a snapshot holds both or neither.
                    for (int pair = 0; pair < 2; pair++) {
                        try (var insert = connection.prepareStatement(
                                "insert into balance (account_id, amount_cents, balance_date, entered_at) values (1, ?, '2026-06-01', 'x')")) {
                            insert.setLong(1, 1000 + i);
                            insert.executeUpdate();
                        }
                    }
                    connection.commit();
                    i++;
                }
            } catch (Exception e) {
                writerFailures.add(e);
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 8; i++) {
                Path file = temp.resolve("b" + i + ".zip");
                backup(folder, file);
                Path check = folder("check" + i);
                BackupService service = service(check);
                service.restore(service.stage(file).token(), "RESTORE");
                assertThat(BackupFixtures.open(check).count("balance") % 2).as("pairs are never torn").isEqualTo(1); // 5 filled + pairs
            }
        } finally {
            stop.set(true);
            writer.join();
        }
        assertThat(writerFailures).isEmpty();
    }

    // ----- undo

    @Test
    void undoingARestorePutsTheOldDatabaseBackAndCanBeUndoneItself() throws Exception {
        Path folder = folder("data");
        BackupFixtures live = BackupFixtures.open(folder).fill();
        Path file = backup(folder, temp.resolve("backup.zip"));
        String atBackup = live.content();
        live.accounts.addManual("Added after the backup", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.CURRENT, "");
        String later = BackupFixtures.open(folder).content();
        assertThat(later).isNotEqualTo(atBackup);

        BackupService service = service(folder);
        assertThat(service.hasPrevious()).isFalse();
        assertThatThrownBy(() -> service.undoRestore("UNDO")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOTHING_TO_UNDO));

        service.restore(service.stage(file).token(), "RESTORE");
        assertThat(content(folder)).isEqualTo(atBackup);
        assertThat(service.hasPrevious()).isTrue();

        assertThatThrownBy(() -> service.undoRestore("yes")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIRMED));
        service.undoRestore("undo");
        assertThat(content(folder)).isEqualTo(later);

        // The restore that was undone is itself kept, so going forward again works.
        assertThat(service.hasPrevious()).isTrue();
        service.undoRestore("UNDO");
        assertThat(content(folder)).isEqualTo(atBackup);
        assertNothingStaged(folder);
    }

    // ----- refusals change nothing

    private void assertRefused(Path folder, Path file, Reason reason) throws Exception {
        String before = content(folder);
        BackupService service = service(folder);
        assertThatThrownBy(() -> service.stage(file)).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
        assertThat(content(folder)).isEqualTo(before);
        assertThat(service.hasPrevious()).isFalse();
        assertNothingStaged(folder);
    }

    @Test
    void aTruncatedFileIsRefusedAndNothingChanges() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path whole = backup(folder, temp.resolve("backup.zip"));
        byte[] bytes = Files.readAllBytes(whole);
        for (int keep : new int[] {bytes.length / 2, bytes.length - 10, 40}) {
            Path cut = temp.resolve("cut" + keep + ".zip");
            Files.write(cut, java.util.Arrays.copyOf(bytes, keep));
            assertRefused(folder, cut, Reason.DAMAGED);
        }
    }

    @Test
    void aFileThatIsNotABackupIsRefused() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path text = Files.writeString(temp.resolve("notes.txt"), "just some text, not a backup");
        Path empty = Files.createFile(temp.resolve("empty.zip"));
        Path someZip = zip("other.zip", entry("readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        Path foreignManifest = zip("foreign.zip", entry("manifest.properties", "format=something-else\nformatVersion=1\n".getBytes(StandardCharsets.UTF_8)));
        Path database = temp.resolve("plain.db");
        Files.copy(folder.resolve("finances.db"), database);
        for (Path file : List.of(text, empty, someZip, foreignManifest, database)) {
            assertRefused(folder, file, Reason.NOT_A_BACKUP);
        }
    }

    @Test
    void aBackupWithANewerFormatVersionIsRefusedWithAClearMessage() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path file = backup(folder, temp.resolve("backup.zip"));
        Path newer = rewriteManifest(file, "newer.zip", text -> text.replace("formatVersion=1", "formatVersion=2"));
        assertRefused(folder, newer, Reason.NEWER_FORMAT);
        assertThatThrownBy(() -> service(folder).stage(newer)).hasMessageContaining("newer version");
    }

    @Test
    void aBackupOfANewerDatabaseIsRefused() throws Exception {
        Path other = folder("other");
        BackupFixtures future = BackupFixtures.open(other).fill();
        future.jdbc.sql("insert into flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)"
                + " values (99, '99', 'future', 'SQL', 'V99__future.sql', 1, 'x', 'now', 1, 1)").update();
        Path file = backup(other, temp.resolve("future.zip"));

        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        assertRefused(folder, file, Reason.NEWER_SCHEMA);
    }

    @Test
    void aDamagedDatabaseInsideIsRefusedByItsChecksum() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path file = backup(folder, temp.resolve("backup.zip"));
        byte[] database;
        String manifest;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            database = zip.getInputStream(zip.getEntry("finances.db")).readAllBytes();
            manifest = new String(zip.getInputStream(zip.getEntry("manifest.properties")).readAllBytes(), StandardCharsets.UTF_8);
        }
        database[database.length / 2] ^= 0x55;
        Path damaged = zip("damaged.zip", entry("manifest.properties", manifest.getBytes(StandardCharsets.UTF_8)), entry("finances.db", database));
        assertRefused(folder, damaged, Reason.DAMAGED);

        // The same size and the right checksum, but not a database SQLite accepts.
        Path garbage = zip("garbage.zip", entry("manifest.properties", manifest.getBytes(StandardCharsets.UTF_8)),
                entry("finances.db", new byte[database.length]));
        assertRefused(folder, garbage, Reason.DAMAGED);
    }

    @Test
    void aRestoreThatCannotBeMigratedLeavesTheRunningDatabaseUntouched() throws Exception {
        Path other = folder("other");
        BackupFixtures broken = BackupFixtures.open(other).fill();
        // A migration history that no longer matches the migrations of this application.
        broken.jdbc.sql("update flyway_schema_history set checksum = 12345 where version = '3'").update();
        Path file = backup(other, temp.resolve("broken.zip"));

        Path folder = folder("data");
        BackupFixtures live = BackupFixtures.open(folder).fill();
        live.accounts.addManual("Only here", finances.accounts.Scope.PRIVATE, finances.accounts.Kind.CURRENT, "");
        String before = BackupFixtures.open(folder).content();

        BackupService service = service(folder);
        RestorePlan plan = service.stage(file);
        assertThatThrownBy(() -> service.restore(plan.token(), "RESTORE")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.MIGRATION_FAILED));

        assertThat(content(folder)).isEqualTo(before);
        assertThat(service.hasPrevious()).isFalse();
        assertThat(Files.list(folder).map(p -> p.getFileName().toString()).filter(n -> !n.equals("tmp") && !n.startsWith("finances.db")).toList())
                .as("no stray file next to the database").isEmpty();
    }

    @Test
    void aRestoreNeedsTheWordAndAKnownUpload() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path file = backup(folder, temp.resolve("backup.zip"));
        BackupService service = service(folder);
        RestorePlan plan = service.stage(file);

        assertThatThrownBy(() -> service.restore(plan.token(), "")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIRMED));
        assertThatThrownBy(() -> service.restore(plan.token(), "yes")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIRMED));
        assertThat(service.staged(plan.token()).manifest()).isEqualTo(plan.manifest());

        for (String token : new String[] {"../../finances", "0".repeat(32), "", "ZZ"}) {
            assertThatThrownBy(() -> service.restore(token, "RESTORE")).isInstanceOfSatisfying(BackupException.class,
                    e -> assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_UPLOAD));
        }
        service.discardStaged(plan.token());
        assertThatThrownBy(() -> service.staged(plan.token())).isInstanceOf(BackupException.class);
        assertNothingStaged(folder);
    }

    @Test
    void aBackupNeedsADatabaseAndNeverOverwritesAFile() throws Exception {
        Path empty = folder("empty");
        assertThatThrownBy(() -> backup(empty, temp.resolve("x.zip"))).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NO_DATABASE));
        assertThat(empty.resolve("finances.db")).as("a backup never creates a database").doesNotExist();

        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path taken = Files.writeString(temp.resolve("taken.zip"), "mine");
        assertThatThrownBy(() -> backup(folder, taken)).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.TARGET_EXISTS));
        assertThat(Files.readString(taken)).isEqualTo("mine");
        assertThat(Files.list(temp).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".part")).toList()).isEmpty();

        Path directory = folder("backups");
        Path made = service(folder).backupTo(directory);
        assertThat(made.getParent()).isEqualTo(directory);
        assertThat(made.getFileName().toString()).isEqualTo("finances-backup-20260615-100000.zip");
        assertThat(Files.getPosixFilePermissions(made)).as("owner only").containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        assertNothingStaged(folder);
    }

    // ----- the gate

    @Test
    void aRestoreWaitsForOpenConnectionsAndIsRefusedWhenTheyStayOpen() throws Exception {
        Path folder = folder("data");
        BackupFixtures.open(folder).fill();
        Path file = backup(folder, temp.resolve("backup.zip"));

        DatabaseGate gate = new DatabaseGate();
        GatedDataSource gated = new GatedDataSource(BackupFixtures.open(folder).dataSource, gate);
        BackupService service = new BackupService(folder, gate, "1.2.3", BackupFixtures.CLOCK, Duration.ofMillis(300));
        String before = content(folder);

        Connection open = gated.getConnection();
        RestorePlan plan = service.stage(file);
        assertThatThrownBy(() -> service.restore(plan.token(), "RESTORE")).isInstanceOfSatisfying(BackupException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.BUSY));
        assertThat(content(folder)).isEqualTo(before);

        // A statement run through the gated connection still works, and once it is closed the restore goes through.
        assertThat(open.createStatement().executeQuery("select count(*) from account").getInt(1)).isEqualTo(4);
        open.close();
        open.close(); // closing twice gives the permit back once
        service.restore(plan.token(), "RESTORE");
        try (Connection again = gated.getConnection()) {
            assertThat(again.createStatement().executeQuery("select count(*) from account").getInt(1)).isEqualTo(4);
        }
    }

    // ----- the stored fixture

    /**
     * A backup of format version 1, made by this version and stored in the repository. Every later
     * version of the application must still restore it (AGENTS.md, hard rule 4): never regenerate
     * this file, add a new one next to it when the format changes.
     */
    @Test
    void theStoredFormat1BackupStillRestores() throws Exception {
        Path folder = folder("data");
        Path file = temp.resolve("format-1.zip");
        Files.copy(Path.of("src/test/fixtures/backup/format-1.zip"), file);
        BackupService service = service(folder);
        RestorePlan plan = service.stage(file);
        assertThat(plan.manifest().formatVersion()).isEqualTo(1);
        assertThat(plan.backup()).isEqualTo(new Summary(4, 5, 5));
        service.restore(plan.token(), "RESTORE");

        BackupFixtures restored = BackupFixtures.open(folder);
        assertThat(restored.count("account")).isEqualTo(4);
        assertThat(restored.accounts.list(false)).extracting(a -> a.account().name())
                .containsExactlyInAnyOrder("Everyday", "Rainy day", "Example Client BV account", "Example Loan");
        assertThat(restored.transactions.all(0).items()).hasSize(5)
                .anySatisfy(t -> assertThat(t.note()).isEqualTo(BackupFixtures.NOTE))
                .anySatisfy(t -> assertThat(t.counterpartyName()).isEqualTo("Example Energy"));
        assertThat(restored.accounts.history(1)).extracting(b -> b.amount().cents()).containsExactly(100000L, 123456L);
    }

    /** Run once to (re)create the stored fixture: {@code ./mvnw test -Dtest=BackupServiceTest -Dfinances.writeBackupFixture=src/test/fixtures/backup/format-N.zip}. */
    @Test
    @EnabledIfSystemProperty(named = "finances.writeBackupFixture", matches = ".+")
    void writeTheFixture() throws Exception {
        Path folder = folder("fixture");
        BackupFixtures.open(folder).fill();
        Path made = backup(folder, temp.resolve("fixture.zip"));
        Files.copy(made, Path.of(System.getProperty("finances.writeBackupFixture")), StandardCopyOption.REPLACE_EXISTING);
    }

    // ----- helpers

    private record Entry(String name, byte[] bytes) {
    }

    private static Entry entry(String name, byte[] bytes) {
        return new Entry(name, bytes);
    }

    private Path zip(String name, Entry... entries) throws IOException {
        Path file = temp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Entry entry : entries) {
                out.putNextEntry(new ZipEntry(entry.name()));
                out.write(entry.bytes());
                out.closeEntry();
            }
        }
        return file;
    }

    private Path rewriteManifest(Path source, String name, java.util.function.UnaryOperator<String> change) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.properties")).readAllBytes(), StandardCharsets.UTF_8);
            byte[] database = zip.getInputStream(zip.getEntry("finances.db")).readAllBytes();
            return zip(name, entry("manifest.properties", change.apply(manifest).getBytes(StandardCharsets.UTF_8)), entry("finances.db", database));
        }
    }

    @SuppressWarnings("unused")
    private static byte[] bytes(ByteArrayOutputStream stream) {
        return stream.toByteArray();
    }
}
