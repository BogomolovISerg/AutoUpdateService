package git.autoupdateservice.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Анализатор логов vanessa-runner.
 *
 * Правила основаны на примерах в "Лог шагов.txt":
 *  - КРИТИЧНАЯОШИБКА => ошибка (в summary выводим только текст ошибки без модуля/строки)
 *  - ОШИБКА - ...    => ошибка (берём последнее сообщение)
 *  - Отдельные "скрытые" ошибки без тега (например "расширение ... не найдено")
 *  - Для SESSION_CLOSED: наличие списка session : ... трактуем как "сессии ещё есть" (для ретраев)
 *
 * Для успеха: в summary берём "результат" из конца лога (после последней строки ОТЛАДКА - ...),
 * либо хвост блока ИНФОРМАЦИЯ для консольных команд (шаг 7).
 */
@Component
public class VanessaRunnerStepLogAnalyzer implements StepLogAnalyzer {

    private static final Pattern ERR_LINE = Pattern.compile("^ОШИБКА\s*-\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern CRIT_LINE =
            Pattern.compile("КРИТИЧНАЯОШИБКА\\s*-\\s*\\{", Pattern.MULTILINE);

       private static final Pattern EXT_NOT_FOUND = Pattern.compile("Операция не может быть выполнена.*не найдено:\\s*(.+)$", Pattern.MULTILINE);

    @Override
    public Analysis analyze(String stepCode, String stdoutText, String stderrText, String debugText) {
        String stdout = nvl(stdoutText);
        String stderr = nvl(stderrText);
        String debug = nvl(debugText);

        String combined = joinNonBlank(debug, stdout, stderr);

        // 1) SESSION_CLOSED: если в логе есть session : ... => сессии не закрыты.
        if (stepCode != null && stepCode.toUpperCase(Locale.ROOT).contains("SESSION_CLOSED")) {
            if (combined.contains("session                          :") || combined.contains("session :")) {
                return Analysis.error("Сеансы не закрыты (найдены активные сессии)");
            }
        }

        // 2) Явные ошибки: ОШИБКА - ... (берём последнюю)
        String lastErr = lastMatchGroup(ERR_LINE, combined, 1);
        if (notBlank(lastErr)) {
            return Analysis.error(lastErr.trim());
        }

        // 3) Критическая ошибка: вырезаем только "человеческое" сообщение
        if (CRIT_LINE.matcher(combined).find()) {
            String msg = extractCriticalMessage(combined);
            if (notBlank(msg)) return Analysis.error(msg);
            return Analysis.error("Критическая ошибка (см. детали)");
        }

        // 4) "Скрытая" ошибка без тега: расширение не найдено
        Matcher m = EXT_NOT_FOUND.matcher(combined);
        if (m.find()) {
            String ext = m.group(1);
            String line = "Расширение не найдено" + (notBlank(ext) ? (": " + ext.trim()) : "");
            return Analysis.error(line);
        }

        // 5) Итог для web: результат из конца лога
        String primary = notBlank(debug) ? debug : joinNonBlank(stdout, stderr);
        String summary = extractResultFromEnd(primary);

        // 6) Для LOADREPO_MAIN добавляем список объектов, полученных из хранилища
        if (stepCode != null && stepCode.toUpperCase(Locale.ROOT).contains("LOADREPO_MAIN")) {
            List<String> objs = extractStorageObjects(primary);
            if (!objs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (notBlank(summary)) sb.append(summary.trim()).append("\n\n");
                sb.append("Получено из хранилища (первые ").append(Math.min(objs.size(), 30)).append("):\n");
                int limit = Math.min(objs.size(), 30);
                for (int i = 0; i < limit; i++) {
                    sb.append("- ").append(objs.get(i)).append('\n');
                }
                if (objs.size() > limit) {
                    sb.append("… и ещё ").append(objs.size() - limit).append(" объект(ов)");
                }
                summary = sb.toString().trim();
            }
        }

        if (!notBlank(summary)) {
            summary = lastInfoBlock(primary);
        }

        summary = trimTo(summary, 3500);
        return new Analysis(false, summary);
    }

    private static String extractCriticalMessage(String text) {
        // 1) если перед КРИТИЧНАЯОШИБКА уже была строка "ОШИБКА - ..." — обычно она самая понятная
        String lastErr = lastMatchGroup(ERR_LINE, text, 1);
        if (notBlank(lastErr)) return lastErr.trim();

        int i = text.lastIndexOf("КРИТИЧНАЯОШИБКА");
        if (i < 0) return null;
        int brace = text.indexOf('{', i);
        if (brace < 0) return null;
        int end = text.lastIndexOf('}');
        if (end <= brace) end = text.length();
        String inner = text.substring(brace + 1, end).trim();

        // Убираем "Модуль ..." и "Ошибка в строке: ..." из формата "{Модуль ... / Ошибка в строке: ... / <сообщение>}"
        List<String> kept = new ArrayList<>();
        for (String part : inner.split("\\s*/\\s*")) {
            String p = part.trim();
            if (p.startsWith("Модуль ")) continue;
            if (p.startsWith("Ошибка в строке:")) continue;
            if (p.isBlank()) continue;
            kept.add(p);
        }
        String msg = String.join(" / ", kept).trim();
        // иногда внутри остаётся вложенное "{Модуль ...}" — выкидываем, если оно ничего не даёт
        msg = msg.replaceAll("\\{Модуль.*?\\}", "").trim();
        msg = msg.replaceAll("\n{3,}", "\n\n").trim();
        return msg;
    }

    private static String extractResultFromEnd(String text) {
        if (!notBlank(text)) return null;
        List<String> lines = Arrays.asList(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));

        int lastDebug = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("ОТЛАДКА -")) lastDebug = i;
        }
        if (lastDebug >= 0) {
            int start = lastDebug + 1;
            while (start < lines.size() && lines.get(start).trim().isEmpty()) start++;
            String out = String.join("\n", lines.subList(start, lines.size())).trim();
            return notBlank(out) ? out : null;
        }

        // Консольные команды: берём последний блок ИНФОРМАЦИЯ
        return lastInfoBlock(text);
    }

    private static String lastInfoBlock(String text) {
        if (!notBlank(text)) return null;
        List<String> lines = Arrays.asList(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
        int end = lines.size() - 1;
        while (end >= 0 && lines.get(end).trim().isEmpty()) end--;
        if (end < 0) return null;

        int start = end;
        while (start >= 0) {
            String ln = lines.get(start);
            if (ln.startsWith("ИНФОРМАЦИЯ -")) {
                start--;
                continue;
            }
            // допускаем продолжение блока (строки без префикса) если сверху была ИНФОРМАЦИЯ
            if (!ln.startsWith("ОТЛАДКА -") && !ln.startsWith("КРИТИЧНАЯОШИБКА") && !ln.startsWith("ОШИБКА")) {
                start--;
                continue;
            }
            break;
        }
        start = Math.max(0, start + 1);
        String out = String.join("\n", lines.subList(start, end + 1)).trim();
        return notBlank(out) ? out : null;
    }

    private static List<String> extractStorageObjects(String text) {
        if (!notBlank(text)) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String ln : text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String s = ln.trim();
            if (s.startsWith("Объект получен из хранилища:")) {
                String obj = s.substring("Объект получен из хранилища:".length()).trim();
                if (!obj.isBlank()) seen.add(obj);
            }
        }
        out.addAll(seen);
        return out;
    }

    private static String lastMatchGroup(Pattern p, String text, int group) {
        if (!notBlank(text)) return null;
        Matcher m = p.matcher(text);
        String last = null;
        while (m.find()) last = m.group(group);
        return last;
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!notBlank(p)) continue;
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(обрезано)";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
