package git.autoupdateservice.util;

import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;

public final class JsonPrettyPrinters {

    private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);

    private JsonPrettyPrinters() {
    }

    public static PrettyPrinter multilineArrays() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(INDENTER);
        printer.indentArraysWith(INDENTER);
        return printer;
    }
}
