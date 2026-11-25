# #9 Import transactions from a CSV file

- **Where**: `finances.importing` (a `risk:high` path already: `src/*/java/finances/importing/**`), the
  pages in `finances.web.ImportController` / `ImportViews`, three templates (`import-upload`,
  `import-preview`, `import-report`). `V4__csv_mapping.sql` adds one table, `csv_mapping`; V1 to V3 are
  untouched. Only one thing in `finances.accounts` changed, and backwards-compatibly (see *Accounts*).
- **One entry point**: every row goes through `TransactionService.deliver(accountId, Source.CSV, batch)`, so
  the same file twice adds nothing (the store's identity rules: reference, else fingerprint + occurrence).
  The report shows the store's `added / updated / known / refused` next to the rows this reader refused.
- **The flow** (stateless pages, one held file): upload, preview (the file's first rows, the settings, the
  column mapping, what would be imported and which rows cannot be read), *Update the preview* after a
  correction, *Import* shows the report. Between the pages the file is kept **in memory** in
  `PendingUploads` under a random token: at most two files, 30 minutes, dropped when the import is done
  (after a restart it has to be uploaded again). It is never written to the data folder. The multipart
  parts Tomcat may spool to the temp folder while a request is read are deleted at the end of the request.
- **Settings** (`CsvMapping`): delimiter (comma, semicolon, tab), encoding (UTF-8 with or without BOM, or
  Windows-1252), header row, date format (a fixed list: `yyyy-MM-dd`, `dd-MM-yyyy`, `dd/MM/yyyy`,
  `dd.MM.yyyy`, `MM/dd/yyyy`, `yyyy/MM/dd`, `yyyyMMdd` and the two-digit-year forms; dates are parsed
  strictly, so `31-02-2026` is refused, not moved), decimal separator (comma or point), and columns by
  number: booking date, amount **or** debit and credit, counterparty name, counterparty IBAN, description,
  reference, balance after. A setting outside these lists, or an amount column together with a debit or
  credit column, is refused (`MAPPING_INVALID`), never guessed. A date and an amount are the least a row
  needs.
- **Detection** (`CsvDetector`) only proposes; the preview shows what it did. Encoding: valid UTF-8 (BOM
  or not) is UTF-8, anything else Windows-1252. Delimiter: the one that gives the most rows the same
  number of columns. Header: a first row without a date above a row with one, or a first row that names a
  date. Date column and format: the one that reads most of the first 60 rows (`dd` before `MM` on a tie;
  the preview shows how the dates were read, and a month-first file is one click). Decimal separator: the
  one the amount cells end with. Columns: by header words, else only the date and the first numeric column;
  a text column is not guessed without a header.
- **Amounts** (`Amounts`): read with the file's decimal separator, the other separator only as grouping of
  thousands in the right place, then by `Money.parse`; so `1,234` with a decimal comma is refused as
  ambiguous and `5.250` with a decimal point likewise. Spaces and no-break spaces are ignored; a currency
  sign or code is a refusal. In a debit or credit column the value is an unsigned magnitude (debit makes
  it negative); exactly one of the two must be filled and a sign in either is a refusal (the direction
  would be a guess). Euro only: nothing in a file converts a currency.
- **Rows**: a row that cannot be read is refused with its number (the first line is row 1) and a reason
  (`RefusalReason`): too few columns, bad date, date in the future, amount empty / not a number /
  ambiguous / too many decimals / too large, debit and credit, sign in debit or credit, bad balance.
  The reason never carries the content (hard rule 11). Blank lines are skipped and not counted. A file
  in which every row fails imports nothing (not even a delivery time, and the mapping is not saved) and the
  report says so; a file with only a header says there is nothing to import. The report lists the first
  200 refused rows and the count is complete.
- **Balance after**: from the newest booking date in the file. When that day holds rows with different
  balances, the order of the file decides (newest first: the first of them; oldest first: the last); a file
  whose first and last rows have the same date cannot tell, and the balance is left alone and the report
  says so. The balance is **added** to the history (`AccountService.recordImportedBalance`), never
  replacing an entry: if the same date and amount is already there nothing is added (importing twice), if a
  later date is already there the file's one is left out (an old file never hides a newer figure), and a
  negative balance on a debt is refused. What the owner typed is never touched. Without a balance column the
  balance is left alone.
- **Mapping saved per account** (`csv_mapping`: settings and column numbers, nothing from the file), only
  after an import that stored something. At the next upload it is offered again when it still fits the
  file (its columns exist and it reads at least one row); otherwise the detector's proposal is shown.
- **Accounts**: `AccountService.add(..., Source)` creates a `manual` or `csv` account (`bank` is refused
  with `INVALID_SOURCE`; `addManual` is unchanged). The source cannot be changed afterwards, and only an
  account whose source is `csv` takes a file (a manual one has no import link, its URL is a 404; an
  archived one is a 409). Balances can be typed in for a `csv` account as for a manual one: `requireEditable`
  now refuses only a `bank` account (the reason name `NOT_MANUAL` is kept). One source per account stays
  the rule of #8: a bank and a CSV import on one account are not deduplicated against each other.
- **Size**: `finances.import.max-file-size` (default `10MB`) feeds Spring's multipart limits;
  `resolve-lazily` makes the limit land inside the controller so it is shown as a page, not a bare 413.
  A 10 MB file is read as text in memory and kept as a list of delivered transactions until the store has
  them; the store reads them one at a time inside one database transaction. That is the largest memory
  use of the feature, and why only two uploads are held.
- **Logging**: nothing in `finances.importing` logs. A test captures the log at DEBUG during imports (good
  and refused) and checks no name, IBAN, reference or amount of the file appears.
- **`raw`**: the row as read, its cells joined with tabs, is stored in `transactions.raw` next to the
  normalised fields, as for the bank.
- **Known limits**: the store's identity is the reference, else a fingerprint of date, amount, counterparty
  and description, so importing the same file again with a *different* mapping (for example the description
  column changed, or the reference column added) can store the rows again. A balance-after on the same date
  as a balance the owner typed, with another amount, is added and becomes the latest one (the owner's entry
  stays in the history); the report says "added to the history". The header row is the first line that is
  not blank.
- **Not done, on purpose**: bank presets, other formats (MT940, CAMT), categories, export, changing an
  account's source, importing into a manual account, a stale limit for the imported balance (its date and age
  are shown, as for manual balances), backing up `csv_mapping` (belongs to the backup issue).
- **Backwards compatibility**: only an added migration and added configuration property; backup format,
  release asset names, health endpoint, after-tax contract, data folder layout untouched.
