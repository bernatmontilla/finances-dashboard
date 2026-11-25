package finances.data;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Prints a data folder problem as a short message with an action instead of a stack trace. */
public class DataFolderFailureAnalyzer extends AbstractFailureAnalyzer<DataFolderException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, DataFolderException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Check the data folder setting (finances.data-dir) and that no other instance uses it.",
                cause);
    }
}
