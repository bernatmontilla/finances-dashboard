package finances.web;

import finances.backup.BackupException;
import finances.backup.BackupService;
import finances.backup.PreparedBackup;
import finances.backup.RestorePlan;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The settings page, for now the Backup section. The rules live in {@code finances.backup}; this
 * class streams the file out, takes the upload in and shows what was refused as a message key.
 * Every action is a POST, so the same-origin filter covers it. Nothing here logs.
 */
@Controller
class SettingsController {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BackupService backups;
    private final Clock clock;

    SettingsController(BackupService backups, Clock clock) {
        this.backups = backups;
        this.clock = clock;
    }

    @GetMapping("/settings")
    String settings(@RequestParam(required = false) String done, @RequestParam(required = false) String failed, Model model) {
        String notice = done == null ? null : "backup.done." + (done.equals("undone") ? "undone" : "restored");
        String error = null;
        for (BackupException.Reason reason : BackupException.Reason.values()) {
            if (reason.name().equalsIgnoreCase(failed)) {
                error = reason.messageKey();
            }
        }
        return settingsPage(model, notice, error);
    }

    private String settingsPage(Model model, String notice, String error) {
        model.addAttribute("hasPrevious", backups.hasPrevious());
        model.addAttribute("previousFrom", backups.previousFrom().map(this::moment).orElse(""));
        model.addAttribute("undoWord", BackupService.UNDO_WORD);
        model.addAttribute("notice", notice);
        model.addAttribute("error", error);
        return "settings";
    }

    // ----- download

    @PostMapping("/settings/backup/download")
    void download(HttpServletResponse response) throws IOException {
        try (PreparedBackup backup = backups.prepare()) {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + backup.fileName() + "\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            backup.writeTo(response.getOutputStream());
            response.flushBuffer();
        } catch (BackupException e) {
            if (!response.isCommitted()) {
                response.sendRedirect("/settings?failed=" + e.reason().name().toLowerCase(Locale.ROOT));
            }
        }
    }

    // ----- restore: upload and check, then confirm

    @PostMapping("/settings/backup/check")
    String check(@RequestParam(value = "file", required = false) MultipartFile file, Model model, HttpServletResponse response) {
        try {
            if (file == null || file.isEmpty()) {
                throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "No file.");
            }
            RestorePlan plan;
            try (InputStream in = file.getInputStream()) {
                plan = backups.stage(in);
            }
            return "redirect:/settings/backup/restore/" + plan.token();
        } catch (IOException e) {
            return refused(model, response, new BackupException(BackupException.Reason.FAILED, "Upload failed.", e));
        } catch (BackupException e) {
            return refused(model, response, e);
        }
    }

    @GetMapping("/settings/backup/restore/{token}")
    String confirmForm(@PathVariable String token, Model model, HttpServletResponse response) {
        try {
            return confirmPage(model, backups.staged(token), null);
        } catch (BackupException e) {
            return refused(model, response, e);
        }
    }

    @PostMapping("/settings/backup/restore/{token}")
    String restore(@PathVariable String token, @RequestParam(defaultValue = "") String confirm, Model model, HttpServletResponse response) {
        try {
            backups.restore(token, confirm);
            return "redirect:/settings?done=restored";
        } catch (BackupException e) {
            if (e.reason() == BackupException.Reason.NOT_CONFIRMED) {
                try {
                    response.setStatus(HttpStatus.BAD_REQUEST.value());
                    return confirmPage(model, backups.staged(token), e.reason().messageKey());
                } catch (BackupException gone) {
                    return refused(model, response, gone);
                }
            }
            return refused(model, response, e);
        }
    }

    @PostMapping("/settings/backup/restore/{token}/cancel")
    String cancel(@PathVariable String token) {
        backups.discardStaged(token);
        return "redirect:/settings";
    }

    private String confirmPage(Model model, RestorePlan plan, String error) {
        model.addAttribute("plan", plan);
        model.addAttribute("manifest", plan.manifest());
        model.addAttribute("madeAt", moment(plan.manifest().createdAt()));
        model.addAttribute("restoreWord", BackupService.RESTORE_WORD);
        model.addAttribute("error", error);
        return "restore-confirm";
    }

    // ----- undo

    @PostMapping("/settings/backup/undo")
    String undo(@RequestParam(defaultValue = "") String confirm, Model model, HttpServletResponse response) {
        try {
            backups.undoRestore(confirm);
            return "redirect:/settings?done=undone";
        } catch (BackupException e) {
            return refused(model, response, e);
        }
    }

    private String refused(Model model, HttpServletResponse response, BackupException e) {
        response.setStatus(e.reason() == BackupException.Reason.BUSY || e.reason() == BackupException.Reason.FAILED
                ? HttpStatus.SERVICE_UNAVAILABLE.value() : HttpStatus.BAD_REQUEST.value());
        return settingsPage(model, null, e.reason().messageKey());
    }

    private String moment(Instant instant) {
        return LocalDateTime.ofInstant(instant, clock.getZone()).format(MOMENT);
    }
}
