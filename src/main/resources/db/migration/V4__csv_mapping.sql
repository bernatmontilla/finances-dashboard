-- How an account's CSV files are read (issue #9): saved at an import, offered again at the next one.
-- Only the settings and column numbers are kept, never a file or anything from it.
create table csv_mapping (
    account_id integer primary key references account (id),
    settings text not null,
    updated_at text not null
);
