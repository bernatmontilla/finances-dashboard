package finances.accounts;

/** One place money sits or is owed. {@code iban} is null when none was given; the currency is always EUR. */
public record Account(long id, String name, Scope scope, Kind kind, Source source, String iban, boolean archived) {
}
