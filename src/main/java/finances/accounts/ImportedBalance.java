package finances.accounts;

/** What recording the balance of an imported file did: added to the history, already there, older than the latest, or refused. */
public enum ImportedBalance {
    UPDATED,
    UNCHANGED,
    OLDER,
    REFUSED
}
