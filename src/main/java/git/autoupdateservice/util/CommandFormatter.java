package git.autoupdateservice.util;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Форматирование команд для запуска через cmd.exe на Windows.
 * Нужен для корректной установки кодовой страницы (chcp) и передачи аргументов с кириллицей.
 */
public final class CommandFormatter {
    private CommandFormatter() {}

    /**
     * Возвращает одну строку для cmd.exe /c: "chcp 65001 >nul && <command...>".
     */
    public static String toCmdExeSingleLine(List<String> command, String windowsCodePage) {
        String cp = (windowsCodePage == null || windowsCodePage.isBlank()) ? "65001" : windowsCodePage.trim();
        return "chcp " + cp + " >nul && " + toCmdLine(command);
    }

    /**
     * Склеивает список аргументов в командную строку cmd.exe с минимальным экранированием.
     */
    public static String toCmdLine(List<String> command) {
        if (command == null || command.isEmpty()) return "";
        return command.stream().map(CommandFormatter::winArg).collect(Collectors.joining(" "));
    }

    private static String winArg(String s) {
        if (s == null) return "\"\"";
        boolean needQuote = s.chars().anyMatch(ch ->
                Character.isWhitespace(ch) || ch == '&' || ch == '|' || ch == '<' || ch == '>' || ch == '^');

        // Для cmd.exe безопаснее удваивать кавычки внутри аргумента.
        String v = s.replace("\"", "\"\"");
        return needQuote ? "\"" + v + "\"" : v;
    }
}
