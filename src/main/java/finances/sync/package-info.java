/**
 * Bank sync (docs/decisions/10-bank-sync.md): linking banks through Enable Banking and fetching their
 * balances and transactions into the accounts and the transactions store. Everything that talks to the
 * bank goes through {@code finances.outbound}; what is stored goes through {@code finances.accounts}.
 * Nothing in here logs a token, session id, IBAN, counterparty or amount (hard rule 11).
 */
package finances.sync;
