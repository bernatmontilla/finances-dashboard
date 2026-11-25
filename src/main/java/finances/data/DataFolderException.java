package finances.data;

/** The data folder is missing, unusable or already in use. The message is safe to print as is. */
public class DataFolderException extends RuntimeException {

    public DataFolderException(String message) {
        super(message);
    }

    public DataFolderException(String message, Throwable cause) {
        super(message, cause);
    }
}
