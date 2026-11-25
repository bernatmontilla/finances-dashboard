# Roadmap

Milestones in order. Each is a slice that can be used end to end, not a layer. Only the next one
is planned in detail (its issues and its plan issue); later ones stay one line until their turn.
Written and changed in a planning round (skill `plan-product`).

## M0: walking skeleton
**Delivers**: an installable, self-updating jar that serves an empty overview page.

Acceptance (checked by hand when the plan is done):
- [ ] `bash scripts/dev/build.sh` passes on a fresh clone, and CI is green on `main`.
- [ ] A merge to `main` produces a GitHub Release with the jar and its checksum file.
- [ ] Following `docs/install.md` gives a running service bound to localhost, with its own data
      folder and a memory cap.
- [ ] The overview page opens at phone and laptop width and shows the version and "no accounts yet".
- [ ] After the next release, the installation runs the new version without manual steps.
- [ ] An update that fails halfway (no network, bad checksum) leaves the old version running.

Plan issue: #13

## M1: accounts
**Delivers**: linked bank accounts, manual accounts and CSV import, every balance and transaction
shown with its age, and backup and restore.

Acceptance:
- [ ] Linking a bank through Enable Banking shows its accounts with balance and transactions.
- [ ] Each account can be given a scope and a kind.
- [ ] *Sync now* twice in a row adds nothing twice.
- [ ] A manual account's balance can be changed, and the history of that balance is shown.
- [ ] Importing the same CSV file twice imports nothing the second time.
- [ ] With the network off, the overview still shows the last balances, marked stale.
- [ ] A consent that is about to expire is announced, and renewing keeps the account's history.
- [ ] Backup, wipe the data folder, restore: everything is back.
- [ ] No token, IBAN or amount appears in the service's log.

Plan issue: #14

## M2: free to spend
**Delivers**: the after-tax source, the two headline numbers with their build-up, reservations and
fixed costs.

Acceptance:
- [ ] With an after-tax source configured, the overview shows *Private free to spend* and
      *Business available* side by side, each with its age, and never a sum of the two.
- [ ] Each number's build-up adds up to it to the cent, and lists the business accounts' gross
      balances apart, not counted.
- [ ] Stopping the after-tax source keeps the last value and marks the business number stale.
- [ ] Without a source configured, no business number is shown and nothing fails.
- [ ] A new reservation lowers the number of its scope by what is reserved so far, and shows the
      amount to set aside per month.
- [ ] Closing a reservation against its payment raises the number again; a yearly one starts over.
- [ ] A fixed cost added by hand appears in the monthly total and, until its charge arrives, in
      *still to come this month*; the headline numbers do not change because of it.
- [ ] After a sync, repeating payments are proposed as fixed costs; confirming one adds it,
      rejecting one keeps it from coming back.
- [ ] A fixed cost whose charge changed price or did not arrive is flagged.
- [ ] Reservations and fixed costs survive a backup and restore.

Plan issue: #23 (without the `plan` label until M1 is accepted)

## Later
- M3 Categorisation: categories, rules, review inbox, transfers, splits, search, notes, CSV export.
- M4 Budgets, monthly and yearly review, forecast.
- M5 Wealth: savings goals, investments, debts and interest, net worth, runway.
- M6 Notifications, and money advanced for others.
