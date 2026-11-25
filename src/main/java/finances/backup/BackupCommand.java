package finances.backup;

import finances.data.DataFolder;
import finances.data.DataFolderException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Backup and restore without the web page, for a scheduled copy to another disk (docs/install.md):
 * {@code java -jar finances-dashboard.jar backup <file or folder>}. A backup is safe while the
 * application runs. A restore needs the application stopped: it takes the data folder's lock. It
 * starts no web server and no Spring context. Prints counts and versions, never amounts or names.
 */
public final class BackupCommand {

    private static final List<String> COMMANDS = List.of("backup", "restore", "undo-restore");

    private BackupCommand() {
    }

    public static boolean handles(String word) {
        return COMMANDS.contains(word);
    }

    /** @return the exit status: 0 done, 1 refused or failed, 2 wrong use or confirmation missing */
    public static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        String command = args[0];
        boolean yes = false;
        String dataDir = environment.get("FINANCES_DATA_DIR");
        List<String> operands = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--yes")) {
                yes = true;
            } else if (args[i].equals("--data-dir") && i + 1 < args.length) {
                dataDir = args[++i];
            } else {
                operands.add(args[i]);
            }
        }
        int expected = command.equals("undo-restore") ? 0 : 1;
        if (dataDir == null || dataDir.isBlank() || operands.size() != expected) {
            err.println(usage());
            return 2;
        }
        try {
            return switch (command) {
                case "backup" -> backup(Path.of(dataDir.strip()).toAbsolutePath().normalize(), Path.of(operands.get(0)), out);
                case "restore" -> restore(dataDir, Path.of(operands.get(0)), yes, out, err);
                default -> undo(dataDir, yes, out, err);
            };
        } catch (BackupException | DataFolderException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static String usage() {
        return """
                Usage: java -jar finances-dashboard.jar <command> [--data-dir <folder>]
                  backup <file or folder>      write a backup (safe while the application runs; never overwrites)
                  restore <backup file> --yes  replace the database with the backup (stop the application first)
                  undo-restore --yes           put back the database from before the last restore
                The data folder is --data-dir or the environment variable FINANCES_DATA_DIR.""";
    }

    private static int backup(Path folder, Path target, PrintStream out) {
        // No lock on purpose: a backup reads one consistent snapshot while the application writes.
        Path file = service(folder).backupTo(target);
        out.println("Backup written: " + file);
        return 0;
    }

    private static int restore(String dataDir, Path file, boolean yes, PrintStream out, PrintStream err) {
        try (DataFolder folder = DataFolder.open(dataDir)) {
            BackupService service = service(folder.path());
            RestorePlan plan = service.stage(file);
            Manifest manifest = plan.manifest();
            out.println("Backup from " + manifest.createdAt() + ", made by version " + manifest.applicationVersion()
                    + " (format " + manifest.formatVersion() + ", database version " + manifest.schemaVersion() + ").");
            out.println("It holds " + plan.backup().accounts() + " accounts, " + plan.backup().balances() + " balances and "
                    + plan.backup().transactions() + " transactions.");
            out.println("This installation now holds " + plan.current().accounts() + " accounts, " + plan.current().balances()
                    + " balances and " + plan.current().transactions() + " transactions, which will be replaced.");
            if (!yes) {
                service.discardStaged(plan.token());
                err.println("Nothing was changed. Run the same command with --yes to restore (the database in use is then kept as finances.previous.db).");
                return 2;
            }
            service.restore(plan.token(), BackupService.RESTORE_WORD);
            out.println("Restored. The database from before is kept as " + DataFolder.PREVIOUS_DATABASE_FILE + "; undo-restore puts it back.");
            return 0;
        }
    }

    private static int undo(String dataDir, boolean yes, PrintStream out, PrintStream err) {
        try (DataFolder folder = DataFolder.open(dataDir)) {
            BackupService service = service(folder.path());
            if (!service.hasPrevious()) {
                err.println("There is no earlier database to go back to.");
                return 1;
            }
            if (!yes) {
                err.println("Nothing was changed. Run the same command with --yes to put back the database from before the last restore.");
                return 2;
            }
            service.undoRestore(BackupService.UNDO_WORD);
            out.println("The database from before the last restore is back in use.");
            return 0;
        }
    }

    private static BackupService service(Path folder) {
        return new BackupService(folder, new DatabaseGate(), version(), Clock.systemUTC());
    }

    private static String version() {
        try (InputStream in = BackupCommand.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("build.version", "unknown");
            }
        } catch (IOException ignored) {
            // the version is informative only
        }
        return "unknown";
    }
}
