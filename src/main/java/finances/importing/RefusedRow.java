package finances.importing;

/** A row that could not be read: its number in the file (the first line is row 1) and why. */
public record RefusedRow(int row, RefusalReason reason) {
}
