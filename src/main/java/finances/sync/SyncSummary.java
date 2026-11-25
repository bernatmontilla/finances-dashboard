package finances.sync;

/** How a call to sync ended, for the page that asked. {@code ok} and {@code failed} count accounts. */
public record SyncSummary(State state, int ok, int failed) {

    public enum State {
        /** The run happened (some accounts may have failed: see {@code failed}). */
        DONE,
        /** Another run was going on; this one did nothing (two runs never overlap). */
        BUSY,
        /** There is no Enable Banking application in the data folder. */
        NOT_CONFIGURED
    }

    static SyncSummary busy() {
        return new SyncSummary(State.BUSY, 0, 0);
    }

    static SyncSummary notConfigured() {
        return new SyncSummary(State.NOT_CONFIGURED, 0, 0);
    }

    public SyncSummary plus(SyncSummary other) {
        return new SyncSummary(state, ok + other.ok, failed + other.failed);
    }
}
