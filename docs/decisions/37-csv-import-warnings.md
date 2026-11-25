# #37 CSV import warns before a changed mapping stores rows twice, and before spending is imported as income

Follows #9 (`docs/decisions/9-csv-import.md`), whose review left three known limits. The transactions store and its
identity rule do not change; only `finances.importing`, the import pages, their texts and tests.

- **Changed mapping** (`CsvImportService.check`): compared with the mapping saved for the account, these fields
  feed an identity and count: reference, counterparty name, counterparty IBAN, description, the amount, debit and
  credit columns, the date column, its format and the encoding. Delimiter, header row, decimal separator and the
  balance column do not (a changed delimiter shifts every column number, so the columns differ anyway). When one
  differs and the account holds transactions with a booking date from the oldest to the newest date of the *readable*
  rows of the file, the preview says so with that count and the import needs `confirmDuplicates`. The count is of
  stored transactions in the range, not of rows that would really be duplicated: the stored ones are not compared
  with the file's. No saved mapping (first import, or only files that stored nothing) means no warning.
- **Refused without the box**: `importFile(..., confirmed=false)` and `importParsed` throw
  `CsvImportException.CONFIRMATION_REQUIRED` before anything is stored, the mapping is not saved, and the page shows
  the preview again (400) with the warning and the box. The three-argument `importFile` stays and does not confirm.
- **Only positive amounts**: one amount column, no debit or credit column, at least one readable row, none negative,
  account kind `current`. A warning only. Savings, investment and debt accounts do not get it (money in is the
  normal direction there).
- **Read once**: whether a saved mapping fits a file reads only its first 60 data rows (`CsvFile.parseHead`); the full
  parse happens once per request (`preview`), and the check and the import work on that `ParsedFile`
  (`importParsed`). Before, an upload read the file twice in full and a confirm read it once more on a refusal.
  The fit test now looks at the first 60 rows only: a file whose first 60 rows all fail but a later one reads no
  longer offers the saved mapping (the detector's proposal is shown instead).
- **Warnings do not rely on colour**: a bordered notice that begins with the word "Warning:" and, for the
  confirmation, a checkbox with its own label.
- **Test change**: `nothingOfTheFileReachesTheLog` imports two different files over the same dates with different
  detected mappings, which now needs the confirmation; it calls `importFile(..., true)` through a new helper. Its
  assertions are unchanged.
- **Backwards compatibility**: nothing outside the import changes; no migration, no new property, backup format
  untouched.
