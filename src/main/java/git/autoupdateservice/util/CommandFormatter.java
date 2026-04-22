package git.autoupdateservice.util;

import java.util.List;
import java.util.stream.Collectors;

public final class CommandFormatter {
    private CommandFormatter() {}

    public static String toCmdExeSingleLine(List<String> command, String windowsCodePage) {
        String cp = (windowsCodePage == null || windowsCodePage.isBlank()) ? "65001" : windowsCodePage.trim();
        return "chcp " + cp + " >nul && " + toCmdLine(command);
    }

    public static String toCmdLine(List<String> command) {
        if (command == null || command.isEmpty()) return "";
        return command.stream().map(CommandFormatter::winArg).collect(Collectors.joining(" "));
    }

    private static String winArg(String s) {
        if (s == null) return "\"\"";
        boolean needQuote = s.chars().anyMatch(ch ->
                Character.isWhitespace(ch) || ch == '&' || ch == '|' || ch == '<' || ch == '>' || ch == '^');

        String v = s.replace("\"", "\"\"");
        return needQuote ? "\"" + v + "\"" : v;
    }
}
