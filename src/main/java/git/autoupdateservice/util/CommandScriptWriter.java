package git.autoupdateservice.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CommandScriptWriter {
    private CommandScriptWriter() {}

    public static void write(List<String> command, Path workDir, Path scriptFile, String windowsCodePage) throws IOException {
        if (command == null || command.isEmpty()) return;
        Files.createDirectories(scriptFile.getParent());

        String text = Platform.isWindows()
                ? renderCmd(command, workDir, windowsCodePage)
                : renderSh(command, workDir);

        Files.writeString(
                scriptFile,
                text,
                Platform.isWindows() ? charsetForWindowsCodePage(windowsCodePage) : StandardCharsets.UTF_8
        );

        try {
            if (!Platform.isWindows()) {
                scriptFile.toFile().setExecutable(true, false);
            }
        } catch (Exception ignored) {
        }
    }

    private static String renderCmd(List<String> command, Path workDir, String windowsCodePage) {
        String cp = normalizeCodePage(windowsCodePage);

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
        if (s == null || s.isEmpty()) return "\"\"";

        boolean need = s.chars().anyMatch(ch ->
                Character.isWhitespace(ch)
                        || ch == '&'
                        || ch == '|'
                        || ch == '<'
                        || ch == '>'
                        || ch == '^'
                        || ch == '('
                        || ch == ')'
                        || ch == '"'
        );

        String v = s.replace("\"", "\"\"");
        return need ? winQuote(v) : v;
    }

    private static String winQuote(String s) {
        return "\"" + s + "\"";
    }

    private static String shQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String normalizeCodePage(String windowsCodePage) {
        if (windowsCodePage == null || windowsCodePage.isBlank()) {
            return "65001";
        }
        return windowsCodePage.trim().replace("\"", "");
    }

    private static Charset charsetForWindowsCodePage(String windowsCodePage) {
        String cp = normalizeCodePage(windowsCodePage);

        if ("65001".equals(cp) || "UTF-8".equalsIgnoreCase(cp) || "UTF8".equalsIgnoreCase(cp)) {
            return StandardCharsets.UTF_8;
        }
        if ("866".equals(cp) || "CP866".equalsIgnoreCase(cp)) {
            return Charset.forName("CP866");
        }
        if ("1251".equals(cp) || "CP1251".equalsIgnoreCase(cp) || "WINDOWS-1251".equalsIgnoreCase(cp)) {
            return Charset.forName("windows-1251");
        }

        if (cp.chars().allMatch(Character::isDigit)) {
            try {
                return Charset.forName("CP" + cp);
            } catch (Exception ignored) {
                return Charset.defaultCharset();
            }
        }

        try {
            return Charset.forName(cp);
        } catch (Exception ignored) {
            return Charset.defaultCharset();
        }
    }
}