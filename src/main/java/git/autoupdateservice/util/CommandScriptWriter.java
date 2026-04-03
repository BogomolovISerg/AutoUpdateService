package git.autoupdateservice.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Пишет "человеческий" скрипт для повторения запуска команды рядом с логами.
 * На Windows — .cmd с переносами через ^ и установкой кодовой страницы (chcp).
 */
public final class CommandScriptWriter {
    private CommandScriptWriter() {}

    public static void write(List<String> command, Path workDir, Path scriptFile, String windowsCodePage) throws IOException {
        if (command == null || command.isEmpty()) return;
        Files.createDirectories(scriptFile.getParent());

        String text = Platform.isWindows()
                ? renderCmd(command, workDir, windowsCodePage)
                : renderSh(command, workDir);

        Files.writeString(scriptFile, text, StandardCharsets.UTF_8);

        // на *nix делаем исполняемым (если ОС позволяет)
        try {
            if (!Platform.isWindows()) {
                scriptFile.toFile().setExecutable(true, false);
            }
        } catch (Exception ignored) {}
    }

    private static String renderCmd(List<String> command, Path workDir, String windowsCodePage) {
        String cp = (windowsCodePage == null || windowsCodePage.isBlank()) ? "65001" : windowsCodePage.trim();

        List<String> lines = new ArrayList<>();
        lines.add("@echo off");
        lines.add("chcp " + cp + " >nul");
        if (workDir != null) {
            lines.add("cd /d " + winQuote(workDir.toAbsolutePath().toString()));
        }

        for (int i = 0; i < command.size(); i++) {
            String arg = winArg(command.get(i));
            boolean last = (i == command.size() - 1);
            if (i == 0) {
                lines.add(arg + (last ? "" : " ^"));
            } else {
                lines.add("  " + arg + (last ? "" : " ^"));
            }
        }

        lines.add("");
        return String.join("\r\n", lines);
    }

    private static String renderSh(List<String> command, Path workDir) {
        List<String> lines = new ArrayList<>();
        lines.add("#!/usr/bin/env bash");
        lines.add("set -euo pipefail");
        if (workDir != null) {
            lines.add("cd " + shQuote(workDir.toAbsolutePath().toString()));
        }

        for (int i = 0; i < command.size(); i++) {
            String arg = shQuote(command.get(i));
            boolean last = (i == command.size() - 1);
            if (i == 0) {
                lines.add(arg + (last ? "" : " \\"));
            } else {
                lines.add("  " + arg + (last ? "" : " \\"));
            }
        }

        lines.add("");
        return String.join("\n", lines);
    }

    private static String winArg(String s) {
        if (s == null) return "\"\"";
        // минимальный safe-режим: если есть пробелы/спецсимволы — берём в кавычки
        boolean need = s.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '&' || ch == '|' || ch == '<' || ch == '>' || ch == '^');
        String v = s.replace("\"", "\\\"");
        return need ? winQuote(v) : v;
    }

    private static String winQuote(String s) {
        return "\"" + s + "\"";
    }

    private static String shQuote(String s) {
        if (s == null) return "''";
        // safe single-quote escaping: ' -> '\''
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
