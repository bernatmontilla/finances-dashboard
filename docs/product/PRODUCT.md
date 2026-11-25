# Product

One page. What this is and is not. Written in a planning round (skill `plan-product`), changed
only in one. `intake` reads it before writing any spec, so what is written here decides what gets
built without asking.

## Purpose
A self-hosted personal finances dashboard. It brings bank accounts, savings, investments and
debts together in one place and shows what is really free to spend: money set aside for taxes or
for upcoming expenses is not counted as spendable. Each installation holds one person's data and
runs on that person's own machine; the repository contains code only.

## Users and usage
A single user per installation, on a private network.
- **Phone, at a glance**: the overview with the headline numbers and what is still to come.
- **Laptop, periodic review**: categorising, the monthly review, and maintaining reservations,
  budgets, fixed costs, investments and debts.

## Features

### Overview
- Two headline numbers, shown side by side and never added together: **Private free to spend**
  and **Business available**. Each opens a build-up that shows how it is calculated.
- **Fixed costs still to come this month**, per scope, shown next to the headline numbers and not
  subtracted from them.
- Every figure carries its age. A figure whose source failed, expired or is too old is marked
  stale.

### Accounts and transactions
- Bank accounts linked through Enable Banking (read-only), with balances and transactions synced
  daily and on demand.
- Manual accounts with a balance that is updated by hand, and transactions imported from CSV.
- Each account has a scope (private or business) and a kind (current, savings, investment, debt).
- Transfers between own accounts are recognised and count as neither income nor spending.
- Search and filter across all transactions; a free-text note per transaction.
- Export of all data to CSV. Backup to a single file, and restore from it.

### After-tax business amount
- The amount available after estimated taxes is read from an external bookkeeping source through
  a small fixed contract ([docs/contracts/after-tax-source.md](../contracts/after-tax-source.md)).
- This amount replaces the gross balance of the business accounts in *Business available*.
- The dashboard performs no tax calculation. Without a configured source, the business number is
  not shown.

### Categorisation
- Transactions are categorised automatically by local rules on counterparty, description or IBAN.
  Each transaction shows which rule matched.
- Correcting a category offers to save the correction as a rule for that counterparty.
- A review inbox lists the transactions that have no category yet.
- A transaction can be split over several categories.
- Part of a payment can be marked as advanced for someone else, with tracking of whether it was
  paid back; the advanced part does not count as own spending.

### Reservations and fixed costs
- A reservation is a virtual pot for an upcoming expense: name, amount, due date, scope,
  optionally repeating yearly. It shows the amount to set aside per month, reduces the headline
  number of its scope, and is closed against the real payment. No money is moved.
- Fixed costs are proposed from repeating transactions and count only after being confirmed; they
  can be edited, rejected or added by hand. Totals are shown per month and per scope.
- A fixed cost that changes price or does not arrive is flagged.

### Budgets, review and forecast
- An optional monthly budget per category, with progress during the month.
- A monthly and a yearly review: income, spending per category compared with the average,
  savings rate and the biggest changes.
- A forecast of balances to the end of the month and the following months, from known income,
  fixed costs and reservations.

### Savings, investments and debt
- Savings, investments and debts as balances over time, and net worth.
- Investments are one value per account, updated by hand or by CSV.
- A debt has an interest rate and a monthly payment, and shows interest per year, remaining
  balance and payoff date.
- Savings goals with a target amount and date, progress and the monthly amount needed.
- Runway: the number of months savings cover average spending, per scope.

### Notifications
- Messages through a configurable channel for: a bank consent about to expire, a sync that keeps
  failing, a fixed cost that changed price, a budget that was passed, a balance below a threshold.

## What must always hold
1. **Read-only.** The app never moves money and never starts a payment.
2. **Nothing leaves the machine** except requests to Enable Banking, the configured after-tax
   source and the configured notification channel. No telemetry, no external fonts, scripts or
   images.
3. **Amounts are integer cents with a currency**, never floating point.
4. **No figure without its age.** A value whose source failed, expired or is older than its limit
   is shown as stale, never as if it were current.
5. **Manually entered data is never lost** by a sync, an import or an upgrade: corrections,
   rules, notes, splits, reservations, fixed costs, budgets, goals, debts, manual balances. Reading
   the same data twice imports nothing twice. Backup and restore are one file.
6. **Secrets and real data live only in the data folder** of the installation: never in the
   repository, in logs or in error messages.

## What it will not do
- No payments or transfers.
- No multiple users and no login system: the app binds to localhost and relies on the private
  network in front of it for access control.
- No bookkeeping, invoices or tax calculation.
- No live stock prices and no per-share positions.
- No currency other than the euro, and no conversion. An account in another currency is refused.
- No cloud AI and no third-party analytics; categorising is local rules only.
- No native mobile app; it is a web page that works well on a phone.
- No receipt scanning and no document storage.
- No interface language other than English (texts are in message files, so one could be added in
  a later round).
- No Docker image for now; it ships as one jar.

## Words
- **Account**: one place money sits or is owed. Has a *scope* and a *kind*.
- **Scope**: `private` or `business`. Every account, reservation and fixed cost has one.
- **Kind**: `current`, `savings`, `investment` or `debt`.
- **Source**: where an account's data comes from: `bank` (Enable Banking), `csv` or `manual`.
- **Consent**: the time-limited approval given at a bank for Enable Banking to read an account.
- **After-tax available**: the amount the external bookkeeping source reports as available after
  estimated taxes.
- **Private free to spend**: balances of private current accounts minus open private reservations.
- **Business available**: after-tax available minus open business reservations.
- **Reservation**: a virtual pot for an upcoming expense. No money moves.
- **Fixed cost**: a confirmed repeating expense with an amount and a rhythm.
- **Transfer**: a pair of transactions between two own accounts.
- **Rule**: a local match on counterparty, description or IBAN that sets a category.
- **Review inbox**: the transactions without a category.
- **Stale**: older than allowed, or from a source that last failed.
- **Holding**: a savings, investment or debt account seen as a balance over time.
- **Runway**: months of average spending covered by savings.
- **Data folder**: the one folder outside the repository that holds the database and the secrets.
