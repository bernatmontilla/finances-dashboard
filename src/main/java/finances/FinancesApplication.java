package finances;

import finances.backup.BackupCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FinancesApplication {

    public static void main(String[] args) {
        // "backup", "restore" and "undo-restore" run without the web application (docs/install.md).
        if (args.length > 0 && BackupCommand.handles(args[0])) {
            System.exit(BackupCommand.run(args, System.getenv(), System.out, System.err));
        }
        SpringApplication.run(FinancesApplication.class, args);
    }
}
