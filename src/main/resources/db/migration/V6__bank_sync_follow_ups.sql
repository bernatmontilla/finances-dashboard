-- Follow-ups from the review of the bank sync (issue #39).

-- A pending bank transaction the bank gave no date at all is stored with the day of the fetch (or the day it was first
-- seen). That is not a date the bank stated, so when the same purchase is booked under a new reference with a booking date
-- before that day, it is still taken over (docs/decisions/10-bank-sync.md). 1 for such a row, 0 for every other row.
alter table transactions add column dated_by_fetch integer not null default 0 check (dated_by_fetch in (0, 1));

-- A random value that is also written next to the consent's session file in the secrets folder. A file whose value is not
-- this one is not this consent's (after a restore, a leftover file of another consent with the same id) and is ignored.
-- Null for a consent made before this version: its file has no value either, and the two match.
alter table bank_consent add column session_tag text;
