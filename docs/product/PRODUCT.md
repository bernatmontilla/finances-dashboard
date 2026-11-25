# Product

One page. What this is and is not, in the owner's words. Written in a planning round (skill
`plan-product`), changed only in one. `intake` reads it before writing any spec, so what is
written here decides what gets built without asking.

## Purpose
A self-hosted personal finances dashboard: one place that shows what I can really spend, where my
money goes, and how savings, investments and debt develop. It answers "can I spend this today?"
honestly, because money that is reserved for taxes or for upcoming expenses is not counted as mine
to spend. The repository is public: anyone can run their own copy; it contains code only, never
anybody's data or setup.

## Who uses it, and when
One person, the owner of the installation. No other users.
- **On the phone, any moment**: a glance at the overview. "What can I spend, private and business?"
- **On a laptop, weekly or monthly**: the sit-down. Clear the review inbox, look at the month,
  adjust reservations, budgets and fixed costs, update investments and debts.

## What it does
Overview
- Shows two headline numbers, never added together: **Private free to spend** and
  **Business available**, each with a tap-through that shows how it is built up.
- Shows **fixed costs still to come this month** next to them, per scope, not subtracted.
- Shows every figure with its age; a figure whose source failed or expired is marked stale.

Accounts and transactions
- Link bank accounts through Enable Banking (read-only) and sync balances and transactions daily
  and on demand.
- Add accounts kept by hand (a balance I update) and import transactions from CSV.
- Mark each account private or business, and as current, savings, investment or debt.
- Recognise transfers between my own accounts; they are neither income nor spending.
- Search and filter all transactions; add a note to a transaction.
- Export everything to CSV. Back up everything to one file and restore from it.

Business available
- Read the after-tax available amount from an external source over a small fixed contract
  ([docs/contracts/after-tax-source.md](../contracts/after-tax-source.md)). This amount replaces
  the gross balance of the business accounts. The dashboard does no tax calculation of its own.

Where it goes
- Categorise transactions automatically with local rules; show which rule matched.
- Correct a category and have the app offer to remember it as a rule for that counterparty.
- Work through a review inbox of uncategorised transactions.
- Split one transaction over several categories.
- Mark part of a payment as advanced for someone else and track whether it came back.

Planning
- Create reservations for upcoming expenses (name, amount, due date, scope, optionally yearly);
  see the amount to set aside per month; tick a reservation off against the real payment.
- Get proposals for fixed costs from repeating transactions; confirm, edit or reject each; add one
  by hand. See the monthly total per scope, and a warning when one changes price or is missing.
- Set an optional monthly budget per category and see how far I am.
- See a forecast to the end of the month and the next months from known income, fixed costs and
  reservations.
- Review a month and a year: income, spending per category against my average, savings rate,
  biggest changes.

Wealth and debt
- See savings, investments and debts as balances over time, and net worth.
- Keep investments by hand as one value per account (updated by me or by CSV).
- Give a debt an interest rate and a monthly payment; see interest per year, remaining balance and
  payoff date.
- Set savings goals (target amount and date) and see progress and the monthly amount needed.
- See the runway: how many months my savings cover average spending, private and business apart.

Notifications
- Be told when a bank consent is about to expire, a sync keeps failing, a fixed cost changes
  price, a budget is passed or a balance runs low, through a channel I configure.

## What must always hold
1. **Read-only.** The app never moves money and never starts a payment.
2. **Nothing leaves the machine** except requests to Enable Banking, the configured after-tax
   source and the configured notification channel. No telemetry, no external fonts, scripts or
   images.
3. **Amounts are integer cents with a currency**, never floating point.
4. **No figure without its age.** A value whose source failed, expired or is older than its limit
   is shown as stale, never as if it were current.
5. **What I entered by hand is never lost** by a sync, an import or an upgrade: corrections,
   rules, notes, splits, reservations, fixed costs, budgets, goals, debts, manual balances. Reading
   the same data twice imports nothing twice. Backup and restore are one file.
6. **Secrets and real data live only in the data folder** of the installation: never in the
   repository, in logs or in error messages.

## What it will not do
- No payments or transfers.
- No multiple users and no login system: the network (for the owner: Tailscale) is the gate. The
  app binds to localhost only.
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
- **Consent**: my time-limited approval at a bank for Enable Banking to read an account.
- **After-tax available**: the amount the external bookkeeping source reports as available after
  estimated taxes.
- **Private free to spend**: balances of private current accounts minus open private reservations.
- **Business available**: after-tax available minus open business reservations.
- **Reservation**: a virtual pot for an upcoming expense. No money moves.
- **Fixed cost**: a confirmed repeating expense with an amount and a rhythm.
- **Transfer**: a pair of transactions between two of my own accounts.
- **Rule**: a local match on counterparty, description or IBAN that sets a category.
- **Review inbox**: the transactions without a category.
- **Stale**: older than allowed, or from a source that last failed.
- **Holding**: a savings, investment or debt account seen as a balance over time.
- **Runway**: months of average spending covered by savings.
- **Data folder**: the one folder outside the repository that holds the database and the secrets.
