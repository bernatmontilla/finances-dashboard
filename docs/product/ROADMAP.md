# Roadmap

Milestones in order. Each is a slice the owner can use end to end, not a layer. Only the next one
is planned in detail (its issues and its plan issue); later ones stay one line until their turn.
Written and changed in a planning round (skill `plan-product`).

## M0: walking skeleton
**After this I can** open the dashboard on my phone over my private network and see an (empty)
overview page served by a released jar that updates itself.

Acceptance (the owner does this by hand when the plan is done):
- [ ] `bash scripts/dev/build.sh` passes on a fresh clone, and CI is green on `main`.
- [ ] A merge to `main` produces a GitHub Release with the jar and its checksum file.
- [ ] Following `docs/install.md` on the Pi gives a running service bound to localhost, with its
      own data folder and a memory cap.
- [ ] The overview page opens on the phone and the laptop and shows the version and "no accounts yet".
- [ ] After the next merge, the Pi runs the new version without me doing anything.
- [ ] Stopping the network on the Pi during an update leaves the old version running.

Plan issue: created when this round is approved.

## M1: accounts
**After this I can** link a bank, add accounts by hand, import a CSV, and see every balance and
transaction with its age, and get all of it back from a backup.

Acceptance:
- [ ] I link one bank through Enable Banking and its accounts appear with balance and transactions.
- [ ] I mark each account private or business and give it a kind.
- [ ] *Sync now* twice in a row adds nothing twice.
- [ ] I add a manual account, change its balance, and see the history of that balance.
- [ ] I import a CSV file twice; the second time imports nothing.
- [ ] With the network off, the overview still shows the last balances, marked stale.
- [ ] The app tells me when a consent is about to expire, and renewing keeps the account's history.
- [ ] I make a backup, wipe the data folder, restore, and everything is back.
- [ ] No token, IBAN or amount appears in the service's log.

Plan issue: created when this round is approved.

## Later
- M2 Free to spend: the after-tax source, the two headline numbers with their build-up,
  reservations, fixed costs.
- M3 Where it goes: categories, rules, review inbox, transfers, splits, search, notes, CSV export.
- M4 Steering: budgets, monthly and yearly review, forecast.
- M5 Wealth: savings goals, investments, debts and interest, net worth, runway.
- M6 Notifications, and money advanced for others.
