package finances.backup;

/** What a database holds, in numbers, so the owner can see what a restore replaces with what. No amounts, no names. */
public record Summary(long accounts, long balances, long transactions) {

    public static final Summary EMPTY = new Summary(0, 0, 0);
}
