package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.importing.CsvImportException;
import finances.importing.CsvImportService;
import finances.importing.CsvMapping;
import finances.importing.ImportReport;
import finances.importing.ParsedFile;
import finances.importing.PendingUploads;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Uploading a CSV file on an account's page: upload, preview with the column mapping, import, report.
 * The rules live in {@code finances.importing}; this class reads the forms and shows what they
 * refuse as message keys. The uploaded file is held in memory between the preview and the import
 * ({@link PendingUploads}) and is never written to the data folder. Nothing of it is logged.
 */
@Controller
class ImportController {

    private final AccountService accounts;
    private final CsvImportService importing;
    private final PendingUploads uploads;

    ImportController(AccountService accounts, CsvImportService importing, PendingUploads uploads) {
        this.accounts = accounts;
        this.importing = importing;
        this.uploads = uploads;
    }

    @GetMapping("/accounts/{id}/import")
    String uploadForm(@PathVariable long id, Model model) {
        return uploadPage(model, importable(id));
    }

    @PostMapping("/accounts/{id}/import")
    String upload(@PathVariable long id, @RequestParam("file") MultipartFile file, Model model, HttpServletResponse response)
            throws IOException {
        Account account = importable(id);
        if (file.isEmpty()) {
            return refused(response, model, new CsvImportException(CsvImportException.Reason.NO_FILE), uploadPage(model, account));
        }
        byte[] bytes = file.getBytes();
        String token = uploads.hold(id, bytes);
        CsvMapping mapping = importing.propose(id, bytes);
        return previewPage(model, account, token, bytes, mapping);
    }

    /** Shows the preview again with the settings the owner changed; nothing is imported. */
    @PostMapping("/accounts/{id}/import/preview")
    String preview(@PathVariable long id, @RequestParam Map<String, String> form, Model model, HttpServletResponse response) {
        Account account = importable(id);
        String token = form.getOrDefault("token", "");
        byte[] bytes = uploads.find(token, id).orElse(null);
        if (bytes == null) {
            return refused(response, model, new CsvImportException(CsvImportException.Reason.UPLOAD_EXPIRED), uploadPage(model, account));
        }
        try {
            return previewPage(model, account, token, bytes, ImportViews.mapping(form));
        } catch (CsvImportException e) {
            return refused(response, model, e, previewPage(model, account, token, bytes, importing.propose(id, bytes)));
        }
    }

    @PostMapping("/accounts/{id}/import/confirm")
    String confirm(@PathVariable long id, @RequestParam Map<String, String> form, Model model, HttpServletResponse response) {
        Account account = importable(id);
        String token = form.getOrDefault("token", "");
        byte[] bytes = uploads.find(token, id).orElse(null);
        if (bytes == null) {
            return refused(response, model, new CsvImportException(CsvImportException.Reason.UPLOAD_EXPIRED), uploadPage(model, account));
        }
        try {
            CsvMapping mapping = ImportViews.mapping(form);
            ImportReport report = importing.importFile(id, bytes, mapping);
            uploads.drop(token); // the file does not outlive the import
            model.addAttribute("view", ImportViews.report(account, report));
            return "import-report";
        } catch (CsvImportException e) {
            return refused(response, model, e, previewPage(model, account, token, bytes, previewMapping(form, id, bytes)));
        }
    }

    /** The multipart limit is checked while the file is read (lazily, inside the handler), so this is where it lands. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(HttpServletRequest request, Model model, HttpServletResponse response) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        long id = Long.parseLong(variables.get("id"));
        return refused(response, model, new CsvImportException(CsvImportException.Reason.TOO_LARGE), uploadPage(model, importable(id)));
    }

    // ----- pages

    private String uploadPage(Model model, Account account) {
        model.addAttribute("account", account);
        return "import-upload";
    }

    private String previewPage(Model model, Account account, String token, byte[] bytes, CsvMapping mapping) {
        ParsedFile parsed = mapping.isComplete() ? importing.preview(bytes, mapping) : null;
        model.addAttribute("view", ImportViews.preview(account, token, mapping, importing.head(bytes, mapping), parsed));
        model.addAttribute("delimiters", ImportViews.DELIMITERS);
        model.addAttribute("encodings", ImportViews.ENCODINGS);
        model.addAttribute("decimals", ImportViews.DECIMALS);
        model.addAttribute("dateFormats", CsvMapping.DATE_FORMATS);
        return "import-preview";
    }

    private CsvMapping previewMapping(Map<String, String> form, long id, byte[] bytes) {
        try {
            return ImportViews.mapping(form);
        } catch (CsvImportException e) {
            return importing.propose(id, bytes);
        }
    }

    // ----- helpers

    private Account importable(long id) {
        Account account = accounts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            return importing.importable(id);
        } catch (CsvImportException | AccountException e) {
            // A manual or bank account has no CSV import, an archived one must be restored first.
            throw new ResponseStatusException(account.archived() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND);
        }
    }

    private String refused(HttpServletResponse response, Model model, RuntimeException e, String view) {
        model.addAttribute("error", errorKey(e));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return view;
    }

    static String errorKey(RuntimeException e) {
        if (e instanceof CsvImportException csv) {
            return "error.import." + csv.reason().name().toLowerCase(Locale.ROOT);
        }
        throw e;
    }
}
