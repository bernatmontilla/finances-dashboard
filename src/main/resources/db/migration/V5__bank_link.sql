-- Linking banks through Enable Banking and syncing them (issue #10). Moments are UTC; the euro only.
-- The session id of a consent is a secret: it lives in a file in the data folder (secrets/), never
-- in this database and never in a backup (docs/decisions/10-bank-sync.md).

-- One authorisation at the bank. Renewing a consent makes a new row; the old one goes when no account
-- is attached to it any more.
create table bank_consent (
    id integer primary key autoincrement,
    bank_name text not null,
    bank_country text not null,
    psu_type text not null check (psu_type in ('personal', 'business')),
    -- The headers the bank wants on a fetch the user started, comma separated, as the bank list gave them.
    required_psu_headers text not null default '',
    created_at text not null,
    valid_until text not null,
    -- 'authorized' until a sync or a check finds it ended; an expired or revoked consent keeps its accounts.
    status text not null check (status in ('authorized', 'expired', 'revoked')),
    status_changed_at text not null
);

-- An account the consent covers. Details come only once, with the session (docs/decisions/6-enable-banking.md),
-- so they are stored here at that moment. `account_id` is null while the account is only offered: nothing
-- becomes an account of the app without the owner's confirmation. `uid` is valid inside its session only; the
-- stable key across consents is `identification_hash` (renewing gives new session ids and new uids).
create table bank_account (
    id integer primary key autoincrement,
    consent_id integer not null references bank_consent (id),
    uid text not null,
    identification_hash text,
    iban text,
    holder_name text,
    details text,
    currency text not null,
    cash_account_type text,
    usage text,
    account_id integer references account (id),
    linked_at text,
    -- When a sync of this account last ended well (the moment it started): where the next one picks up.
    last_success_at text,
    unique (consent_id, uid)
);

create unique index bank_account_by_account on bank_account (account_id) where account_id is not null;
create unique index bank_account_by_hash on bank_account (identification_hash)
    where account_id is not null and identification_hash is not null;

-- One row per account per sync run: when, how it was started, how it ended and what the store reported.
-- `reason` is a short code, never a message from the bank.
create table sync_run (
    id integer primary key autoincrement,
    account_id integer not null references account (id),
    started_at text not null,
    finished_at text not null,
    -- scheduled: the daily run; user: "Sync now"; link: the first sync of the link flow.
    trigger text not null check (trigger in ('scheduled', 'user', 'link')),
    -- 1 when the user's own headers went with the fetch (an online fetch), 0 for a background fetch.
    headers_sent integer not null default 0 check (headers_sent in (0, 1)),
    outcome text not null check (outcome in ('ok', 'failed')),
    reason text,
    added integer not null default 0,
    updated integer not null default 0,
    known integer not null default 0,
    refused integer not null default 0,
    pending_removed integer not null default 0,
    pending_kept integer not null default 0
);

create index sync_run_by_start on sync_run (started_at desc, id desc);

-- A bank balance keeps which type it is and how old it is. Manual balances leave these columns empty
-- and are stored and read as before. `as_of_basis`: 'bank_time' the bank's own timestamp, 'bank_date' only a
-- reference date from the bank, 'fetched' the bank said nothing about its age (then `as_of` is when we fetched).
alter table balance add column balance_type text;
alter table balance add column as_of text;
alter table balance add column as_of_basis text check (as_of_basis is null or as_of_basis in ('bank_time', 'bank_date', 'fetched'));

-- A pending transaction the bank stopped reporting that carries a note of the owner is kept and marked.
alter table transactions add column no_longer_reported_at text;
