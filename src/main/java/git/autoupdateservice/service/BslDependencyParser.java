package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencyCallerType;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BslDependencyParser {

    private static final Pattern START_MEMBER = Pattern.compile("(?iu)^\\s*(Процедура|Функция)\\s+([\\p{L}_#][\\p{L}\\p{N}_#]*)");
    private static final Pattern END_MEMBER = Pattern.compile("(?iu)^\\s*Конец(Процедуры|Функции)\\s*;?");
    private static final Pattern QUALIFIED_CALL = Pattern.compile("(?iu)\\b([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\.\\s*([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\(");
    private static final Pattern LOCAL_CALL = Pattern.compile("(?iu)\\b([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\(");

    private static final Set<String> RESERVED_WORDS = Set.of(
            "если", "тогда", "иначе", "иначеесли", "конецесли",
            "для", "каждого", "из", "по", "цикл", "конеццикла",
            "пока", "возврат", "новый", "попытка", "исключение",
            "конецпопытки", "прервать", "продолжить", "выполнить", "вызватьисключение"
    );

    private final OneCNameDecoder oneCNameDecoder;

    public BslDependencyParser(OneCNameDecoder oneCNameDecoder) {
        this.oneCNameDecoder = oneCNameDecoder;
    }

    public ParsedFile parse(
            Path file,
            String fileText,
            Path sourceRoot,
            Set<String> knownCommonModules,
            Set<String> excludedCallNames
    ) {
        String rel = normalize(sourceRoot.relativize(file));
        String decodedRel = oneCNameDecoder.decodePathSegment(rel);

        ParsedOwner owner = determineOwner(decodedRel);
        if (owner == null) {
            return ParsedFile.builder().callerType(null).callerName(null).calls(List.of()).build();
        }

        String[] lines = fileText.split("\\R", -1);
        Set<String> localMembers = collectMemberNames(lines);
        Map<String, String> commonModulesByLower = toCaseInsensitiveMap(knownCommonModules);
        Set<String> excludedLower = toLowercaseSet(excludedCallNames);

        List<ParsedCall> calls = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();
        String currentMember = null;

        for (String rawLine : lines) {
            String line = stripComment(rawLine);
            Matcher start = START_MEMBER.matcher(line);
            boolean declarationLine = false;
            if (start.find()) {
                currentMember = start.group(2);
                declarationLine = true;
            }

            if (currentMember != null && !declarationLine) {
                collectQualifiedCalls(decodedRel, owner, currentMember, line, commonModulesByLower, dedup, calls);
                if (owner.callerType() == DependencyCallerType.COMMON_MODULE) {
                    collectLocalCalls(decodedRel, owner, currentMember, line, localMembers, excludedLower, dedup, calls);
                }
            }

            Matcher end = END_MEMBER.matcher(line);
            if (end.find()) {
                currentMember = null;
            }
        }

        return ParsedFile.builder()
                .callerType(owner.callerType())
                .callerName(owner.callerName())
                .calls(calls)
                .build();
    }

    private void collectQualifiedCalls(
            String decodedRel,
            ParsedOwner owner,
            String currentMember,
            String line,
            Map<String, String> commonModulesByLower,
            Set<String> dedup,
            List<ParsedCall> calls
    ) {
        Matcher matcher = QUALIFIED_CALL.matcher(line);
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2);
            String moduleName = commonModulesByLower.get(left.toLowerCase(Locale.ROOT));
            if (moduleName == null) {
                continue;
            }
            addCall(decodedRel, owner, currentMember, moduleName, right, dedup, calls);
        }
    }

    private void collectLocalCalls(
            String decodedRel,
            ParsedOwner owner,
            String currentMember,
            String line,
            Set<String> localMembers,
            Set<String> excludedLower,
            Set<String> dedup,
            List<ParsedCall> calls
    ) {
        Matcher matcher = LOCAL_CALL.matcher(line);
        while (matcher.find()) {
            int start = matcher.start(1);
            if (isQualifiedCall(line, start)) {
                continue;
            }

            String candidate = matcher.group(1);
            String candidateLower = candidate.toLowerCase(Locale.ROOT);
            if (RESERVED_WORDS.contains(candidateLower) || excludedLower.contains(candidateLower)) {
                continue;
            }
            if (!localMembers.contains(candidate)) {
                continue;
            }
            addCall(decodedRel, owner, currentMember, owner.callerName(), candidate, dedup, calls);
        }
    }

    private void addCall(
            String decodedRel,
            ParsedOwner owner,
            String currentMember,
            String calleeModule,
            String calleeMember,
            Set<String> dedup,
            List<ParsedCall> calls
    ) {
        String key = owner.callerType() + "|" + owner.callerName() + "|" + nvl(currentMember) + "|" + calleeModule + "|" + nvl(calleeMember) + "|" + decodedRel;
        if (!dedup.add(key)) {
            return;
        }

        calls.add(ParsedCall.builder()
                .callerMember(currentMember)
                .calleeModule(calleeModule)
                .calleeMember(calleeMember)
                .sourcePath(decodedRel)
                .build());
    }

    private ParsedOwner determineOwner(String decodedRel) {
        String[] parts = decodedRel.split("/");
        if (parts.length < 2) return null;

        String root = parts[0].toLowerCase(Locale.ROOT);
        String name = parts[1];

        return switch (root) {
            case "commonmodules" -> new ParsedOwner(DependencyCallerType.COMMON_MODULE, name);
            case "catalogs" -> new ParsedOwner(DependencyCallerType.CATALOG, name);
            case "documents" -> new ParsedOwner(DependencyCallerType.DOCUMENT, name);
            case "reports" -> new ParsedOwner(DependencyCallerType.REPORT, name);
            case "commonforms" -> new ParsedOwner(DependencyCallerType.COMMON_FORM, name);
            case "dataprocessors" -> new ParsedOwner(DependencyCallerType.DATA_PROCESSOR, name);
            default -> null;
        };
    }

    private Set<String> collectMemberNames(String[] lines) {
        Set<String> names = new LinkedHashSet<>();
        for (String rawLine : lines) {
            String line = stripComment(rawLine);
            Matcher start = START_MEMBER.matcher(line);
            if (start.find()) {
                names.add(start.group(2));
            }
        }
        return names;
    }

    private Map<String, String> toCaseInsensitiveMap(Set<String> values) {
        Map<String, String> out = new HashMap<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            out.put(value.toLowerCase(Locale.ROOT), value);
        }
        return out;
    }

    private Set<String> toLowercaseSet(Set<String> values) {
        Set<String> out = new HashSet<>();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private boolean isQualifiedCall(String line, int tokenStart) {
        int i = tokenStart - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i))) {
            i--;
        }
        return i >= 0 && line.charAt(i) == '.';
    }

    private String stripComment(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private record ParsedOwner(DependencyCallerType callerType, String callerName) {}

    @Value
    @Builder
    public static class ParsedFile {
        DependencyCallerType callerType;
        String callerName;
        List<ParsedCall> calls;
    }

    @Value
    @Builder
    public static class ParsedCall {
        String callerMember;
        String calleeModule;
        String calleeMember;
        String sourcePath;
    }
}
