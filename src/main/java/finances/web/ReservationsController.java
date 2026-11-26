package finances.web;

import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.Reservation;
import finances.accounts.ReservationException;
import finances.accounts.ReservationService;
import finances.accounts.ReservationSummary;
import finances.accounts.Scope;
import finances.money.AmountFormatException;
import finances.money.Money;
import finances.sync.HeadlineService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The reservations pages (issue #20). Rules live in {@code ReservationService} and {@code finances.money.Reserving}; this class
 * reads the forms, calls them and shows what they refuse as message keys. A refused form is shown again, filled in, with status
 * 400. Nothing here adds or compares amounts.
 */
@Controller
class ReservationsController {

    private static final List<String> SCOPE_CODES = List.of("private", "business");

    private final ReservationService reservations;
    private final AccountService accounts;
    private final HeadlineService headlines;

    ReservationsController(ReservationService reservations, AccountService accounts, HeadlineService headlines) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.headlines = headlines;
    }

    // ----- the list

    @GetMapping("/reservations")
    String list(Model model) {
        return listPage(model);
    }

    private String listPage(Model model) {
        boolean source = headlines.businessSourceConfigured();
        List<ReservationViews.Group> groups = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            ReservationViews.Group group = ReservationViews.group(scope, reservations.summary(scope, 0), reservations.openStandings(scope),
                    reservations.closed(scope), reservations::payment, accounts.zone(), source);
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        model.addAttribute("groups", groups);
        return "reservations";
    }

    // ----- one reservation: add, edit

    @GetMapping("/reservations/new")
    String newForm(Model model) {
        return form(model, null, "", "private", "", "", false, "");
    }

    @PostMapping("/reservations")
    String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String due,
            @RequestParam(defaultValue = "") String yearly, @RequestParam(defaultValue = "") String reserved, Model model,
            HttpServletResponse response) {
        try {
            reservations.create(name, Scope.parse(scope), Money.parse(amount), parseDate(due), !yearly.isBlank(),
                    reserved.isBlank() ? null : Money.parse(reserved));
            return "redirect:/reservations";
        } catch (ReservationException | AccountException | AmountFormatException e) {
            return refused(response, model, e, form(model, null, name, scope, amount, due, !yearly.isBlank(), reserved));
        }
    }

    @GetMapping("/reservations/{id}/edit")
    String editForm(@PathVariable long id, Model model) {
        Reservation reservation = openOne(id);
        return form(model, reservation, reservation.name(), reservation.scope().code(), AccountsController.plainAmount(reservation.amount()),
                reservation.dueDate().toString(), reservation.yearly(), "");
    }

    @PostMapping("/reservations/{id}")
    String update(@PathVariable long id, @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String due,
            @RequestParam(defaultValue = "") String yearly, Model model, HttpServletResponse response) {
        Reservation reservation = openOne(id);
        try {
            reservations.update(id, name, Scope.parse(scope), Money.parse(amount), parseDate(due), !yearly.isBlank());
            return "redirect:/reservations";
        } catch (ReservationException | AccountException | AmountFormatException e) {
            return refused(response, model, e, form(model, reservation, name, scope, amount, due, !yearly.isBlank(), ""));
        }
    }

    @PostMapping("/reservations/{id}/reserved")
    String setReserved(@PathVariable long id, @RequestParam(defaultValue = "") String reserved, Model model, HttpServletResponse response) {
        Reservation reservation = openOne(id);
        try {
            reservations.setReserved(id, Money.parse(reserved));
            return "redirect:/reservations";
        } catch (ReservationException | AmountFormatException e) {
            return refused(response, model, e, form(model, reservation, reservation.name(), reservation.scope().code(),
                    AccountsController.plainAmount(reservation.amount()), reservation.dueDate().toString(), reservation.yearly(), reserved));
        }
    }

    // ----- close, reopen, remove

    /** The picker: the recent booked payments of the scope, likely matches first (issue #51). */
    @GetMapping("/reservations/{id}/close")
    String closeForm(@PathVariable long id, Model model) {
        return closePage(model, openOne(id));
    }

    private String closePage(Model model, Reservation reservation) {
        model.addAttribute("reservation", ReservationViews.row(reservations.standing(reservation)));
        model.addAttribute("candidates", reservations.candidates(reservation.id()).stream().map(c -> ReservationViews.candidate(c)).toList());
        return "reservation-close";
    }

    @PostMapping("/reservations/{id}/close")
    String close(@PathVariable long id, @RequestParam(defaultValue = "") String transaction, Model model, HttpServletResponse response) {
        Reservation reservation = found(id);
        try {
            reservations.close(id, parseTransaction(transaction));
            return "redirect:/reservations";
        } catch (ReservationException e) {
            boolean stillOpen = reservation.isOpen() && e.reason() != ReservationException.Reason.ALREADY_CLOSED;
            return refused(response, model, e, stillOpen ? closePage(model, reservation) : listPage(model));
        }
    }

    @PostMapping("/reservations/{id}/reopen")
    String reopen(@PathVariable long id, Model model, HttpServletResponse response) {
        found(id);
        try {
            reservations.reopen(id);
            return "redirect:/reservations";
        } catch (ReservationException e) {
            return refused(response, model, e, listPage(model));
        }
    }

    @PostMapping("/reservations/{id}/remove")
    String remove(@PathVariable long id, Model model, HttpServletResponse response) {
        found(id);
        try {
            reservations.remove(id);
            return "redirect:/reservations";
        } catch (ReservationException e) {
            return refused(response, model, e, listPage(model));
        }
    }

    // ----- pages and helpers

    private String form(Model model, Reservation reservation, String name, String scope, String amount, String due, boolean yearly,
            String reserved) {
        model.addAttribute("reservation", reservation);
        model.addAttribute("standing", reservation == null ? null : ReservationViews.row(reservations.standing(reservation)));
        model.addAttribute("name", name);
        model.addAttribute("scope", scope);
        model.addAttribute("amount", amount);
        model.addAttribute("due", due);
        model.addAttribute("yearly", yearly);
        model.addAttribute("reserved", reserved);
        model.addAttribute("scopes", SCOPE_CODES);
        model.addAttribute("yesValue", "yes");
        model.addAttribute("today", accounts.today().toString());
        return "reservation-form";
    }

    private String refused(HttpServletResponse response, Model model, RuntimeException e, String view) {
        model.addAttribute("error", errorKey(e));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return view;
    }

    static String errorKey(RuntimeException e) {
        if (e instanceof ReservationException reservation) {
            return "error.reservation." + reservation.reason().name().toLowerCase(Locale.ROOT);
        }
        return AccountsController.errorKey(e);
    }

    private Reservation found(long id) {
        return reservations.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** A closed reservation is not edited: it is reopened first. */
    private Reservation openOne(long id) {
        Reservation reservation = found(id);
        if (!reservation.isOpen()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return reservation;
    }

    /** Blank means "no transaction"; anything that is not a number is a transaction that does not exist. */
    private static Long parseTransaction(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new ReservationException(ReservationException.Reason.TRANSACTION_NOT_FOUND);
        }
    }

    private static LocalDate parseDate(String date) {
        if (date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new ReservationException(ReservationException.Reason.DUE_DATE_INVALID);
        }
    }
}
