package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencyCallerType;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BslDependencyParser {

    private static final Pattern START_MEMBER =
            Pattern.compile("(?iu)^\\s*(Процедура|Функция)\\s+([\\p{L}_#][\\p{L}\\p{N}_#]*)");

    private static final Pattern END_MEMBER =
            Pattern.compile("(?iu)^\\s*Конец(Процедуры|Функции)\\s*;?");

    private static final Pattern QUALIFIED_CALL =
            Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_#])([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\.\\s*([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\(");

    private static final Pattern LOCAL_CALL =
            Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_#])([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\(");

    private static final Pattern EXPORT_WORD =
            Pattern.compile("(?iu)(?<!\\p{L})Экспорт(?!\\p{L})");

    private static final Pattern CONTINUE_CALL =
            Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_#])ПродолжитьВызов\\s*\\(");

    private static final Pattern ANNOTATION_WITH_TARGET =
            Pattern.compile("(?iu)^\\s*&\\s*([\\p{L}_#][\\p{L}\\p{N}_#]*)\\s*\\(\\s*\"([^\"]+)\"");

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

    public ParsedCommonModule parseCommonModule(
            Path file,
            String fileText,
            Path sourceRoot,
            Set<String> knownCommonModules,
            Set<String> excludedCallNames
    ) {
        String rel = normalize(sourceRoot.relativize(file));
        String decodedRel = oneCNameDecoder.decodePath(rel);

        ParsedOwner owner = determineOwner(decodedRel);
        if (owner == null || owner.callerType() != DependencyCallerType.COMMON_MODULE) {
            return null;
        }

        List<MemberBlock> memberBlocks = extractMemberBlocks(fileText);
        Map<String, String> localMembersByLower = new HashMap<>();
        List<MemberDefinition> members = new ArrayList<>();

        for (MemberBlock block : memberBlocks) {
            localMembersByLower.put(block.memberName().toLowerCase(Locale.ROOT), block.memberName());
            members.add(MemberDefinition.builder()
                    .moduleName(owner.callerName())
                    .memberName(block.memberName())
                    .effectiveMemberName(block.effectiveMemberName())
                    .exported(block.exported())
                    .extensionHook(block.extensionHook())
                    .annotationName(block.annotationName())
                    .annotationMode(block.annotationMode())
                    .continueCall(block.continueCall())
                    .fullName(owner.callerName() + "." + block.memberName())
                    .build());
        }

        Map<String, String> commonModulesByLower = toCaseInsensitiveMap(knownCommonModules);
        Set<String> excludedLower = toLowercaseSet(excludedCallNames);

        List<ModuleCall> calls = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        for (MemberBlock block : memberBlocks) {
            collectQualifiedCalls(decodedRel, owner.callerName(), block.memberName(), block.body(), commonModulesByLower, dedup, calls);
            collectLocalCalls(decodedRel, owner.callerName(), block.memberName(), block.body(), localMembersByLower, excludedLower, dedup, calls);
        }

        return ParsedCommonModule.builder()
                .moduleName(owner.callerName())
                .sourcePath(decodedRel)
                .members(members)
                .calls(calls)
                .build();
    }

    public ParsedObjectUsages parseObjectExportUsages(
            Path file,
            String fileText,
            Path sourceRoot,
            Map<String, Set<String>> exportMembersByModule
    ) {
        String rel = normalize(sourceRoot.relativize(file));
        String decodedRel = oneCNameDecoder.decodePath(rel);

        ParsedOwner owner = determineOwner(decodedRel);
        if (owner == null || owner.callerType() == DependencyCallerType.COMMON_MODULE) {
            return null;
        }

        String stripped = stripComments(fileText);
        Matcher matcher = QUALIFIED_CALL.matcher(stripped);
        List<ObjectExportUsage> usages = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        while (matcher.find()) {
            String module = matcher.group(1);
            String member = matcher.group(2);

            Set<String> exportMembers = exportMembersByModule.get(module.toLowerCase(Locale.ROOT));
            if (exportMembers == null || !exportMembers.contains(member.toLowerCase(Locale.ROOT))) {
                continue;
            }

            String key = owner.callerType() + "|" + owner.callerName() + "|" + decodedRel + "|" + module + "|" + member;
            if (!dedup.add(key)) {
                continue;
            }

            usages.add(ObjectExportUsage.builder()
                    .exportModule(module)
                    .exportMember(member)
                    .build());
        }

        return ParsedObjectUsages.builder()
                .objectType(owner.callerType())
                .objectName(owner.callerName())
                .sourcePath(decodedRel)
                .usages(usages)
                .build();
    }

    private List<MemberBlock> extractMemberBlocks(String fileText) {
        String[] lines = fileText.split("\\R", -1);
        List<MemberBlock> result = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String firstLine = stripComment(lines[i]);
            Matcher start = START_MEMBER.matcher(firstLine);
            if (!start.find()) {
                continue;
            }

            String memberName = start.group(2);
            AnnotationInfo annotationInfo = extractAnnotationInfo(lines, i);

            StringBuilder header = new StringBuilder();
            int j = i;
            int parenBalance = 0;
            boolean sawParen = false;

            while (j < lines.length) {
                String headerLine = stripComment(lines[j]);
                header.append(headerLine).append('\n');

                for (int k = 0; k < headerLine.length(); k++) {
                    char ch = headerLine.charAt(k);
                    if (ch == '(') {
                        parenBalance++;
                        sawParen = true;
                    } else if (ch == ')') {
                        parenBalance--;
                    }
                }

                if (!sawParen || parenBalance <= 0) {
                    break;
                }

                j++;
            }

            boolean exported = EXPORT_WORD.matcher(header.toString()).find();

            StringBuilder body = new StringBuilder();
            j++;

            for (; j < lines.length; j++) {
                String bodyLine = stripComment(lines[j]);
                Matcher end = END_MEMBER.matcher(bodyLine);
                if (end.find()) {
                    String bodyText = body.toString();
                    boolean continueCall = CONTINUE_CALL.matcher(bodyText).find();
                    String effectiveMemberName = annotationInfo.targetMemberName() == null || annotationInfo.targetMemberName().isBlank()
                            ? memberName
                            : annotationInfo.targetMemberName();

                    result.add(new MemberBlock(
                            memberName,
                            effectiveMemberName,
                            bodyText,
                            exported,
                            annotationInfo.annotationName(),
                            annotationInfo.annotationMode(),
                            annotationInfo.targetMemberName() != null && !annotationInfo.targetMemberName().isBlank(),
                            continueCall
                    ));
                    i = j;
                    break;
                }

                body.append(bodyLine).append('\n');
            }
        }

        return result;
    }

    private AnnotationInfo extractAnnotationInfo(String[] lines, int memberStartLine) {
        for (int i = memberStartLine - 1; i >= 0; i--) {
            String line = stripComment(lines[i]).trim();
            if (line.isBlank()) {
                break;
            }
            if (!line.startsWith("&")) {
                break;
            }

            Matcher matcher = ANNOTATION_WITH_TARGET.matcher(line);
            if (matcher.find()) {
                String annotationName = matcher.group(1);
                String targetMemberName = matcher.group(2) == null ? null : matcher.group(2).trim();
                return new AnnotationInfo(annotationName, mapAnnotationMode(annotationName), targetMemberName);
            }
        }
        return new AnnotationInfo(null, AnnotationMode.NONE, null);
    }

    private AnnotationMode mapAnnotationMode(String annotationName) {
        if (annotationName == null || annotationName.isBlank()) {
            return AnnotationMode.NONE;
        }
        String normalized = annotationName.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "вместо" -> AnnotationMode.INSTEAD;
            case "после" -> AnnotationMode.AFTER;
            case "перед" -> AnnotationMode.BEFORE;
            default -> AnnotationMode.OTHER;
        };
    }

    private void collectQualifiedCalls(
            String decodedRel,
            String moduleName,
            String currentMember,
            String body,
            Map<String, String> commonModulesByLower,
            Set<String> dedup,
            List<ModuleCall> calls
    ) {
        Matcher matcher = QUALIFIED_CALL.matcher(body);
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(2);
            String calleeModule = commonModulesByLower.get(left.toLowerCase(Locale.ROOT));
            if (calleeModule == null) {
                continue;
            }
            addCall(decodedRel, moduleName, currentMember, calleeModule, right, true, dedup, calls);
        }
    }

    private void collectLocalCalls(
            String decodedRel,
            String moduleName,
            String currentMember,
            String body,
            Map<String, String> localMembersByLower,
            Set<String> excludedLower,
            Set<String> dedup,
            List<ModuleCall> calls
    ) {
        Matcher matcher = LOCAL_CALL.matcher(body);
        while (matcher.find()) {
            int start = matcher.start(1);
            if (isQualifiedCall(body, start)) {
                continue;
            }

            String candidate = matcher.group(1);
            String candidateLower = candidate.toLowerCase(Locale.ROOT);

            if (RESERVED_WORDS.contains(candidateLower) || excludedLower.contains(candidateLower)) {
                continue;
            }

            String canonicalMember = localMembersByLower.get(candidateLower);
            if (canonicalMember == null) {
                continue;
            }
            if (currentMember != null && candidateLower.equals(currentMember.toLowerCase(Locale.ROOT))) {
                continue;
            }

            addCall(decodedRel, moduleName, currentMember, moduleName, canonicalMember, false, dedup, calls);
        }
    }

    private void addCall(
            String decodedRel,
            String callerModule,
            String callerMember,
            String calleeModule,
            String calleeMember,
            boolean logicalCall,
            Set<String> dedup,
            List<ModuleCall> calls
    ) {
        String key = callerModule + "|" + nvl(callerMember) + "|" + calleeModule + "|" + nvl(calleeMember) + "|" + logicalCall + "|" + decodedRel;
        if (!dedup.add(key)) {
            return;
        }

        calls.add(ModuleCall.builder()
                .callerModule(callerModule)
                .callerMember(callerMember)
                .calleeModule(calleeModule)
                .calleeMember(calleeMember)
                .logicalCall(logicalCall)
                .sourcePath(decodedRel)
                .build());
    }

    private ParsedOwner determineOwner(String decodedRel) {
        String[] parts = decodedRel.split("/");
        if (parts.length < 2) {
            return null;
        }

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
        Set<String> out = new LinkedHashSet<>();
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

    private boolean isQualifiedCall(String text, int tokenStart) {
        int i = tokenStart - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i >= 0 && text.charAt(i) == '.';
    }

    private String stripComments(String text) {
        String[] lines = text.split("\\R", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (String line : lines) {
            out.append(stripComment(line)).append('\n');
        }
        return out.toString();
    }

    private String stripComment(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }

        boolean inString = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char ch = line.charAt(i);
            char next = line.charAt(i + 1);

            if (ch == '"') {
                if (inString && next == '"') {
                    i++;
                    continue;
                }
                inString = !inString;
                continue;
            }

            if (!inString && ch == '/' && next == '/') {
                return line.substring(0, i);
            }
        }

        return line;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private record ParsedOwner(DependencyCallerType callerType, String callerName) {
    }

    private record AnnotationInfo(String annotationName, AnnotationMode annotationMode, String targetMemberName) {
    }

    private record MemberBlock(
            String memberName,
            String effectiveMemberName,
            String body,
            boolean exported,
            String annotationName,
            AnnotationMode annotationMode,
            boolean extensionHook,
            boolean continueCall
    ) {
    }

    public enum AnnotationMode {
        NONE,
        BEFORE,
        AFTER,
        INSTEAD,
        OTHER
    }

    @Value
    @Builder
    public static class MemberDefinition {
        String moduleName;
        String memberName;
        String effectiveMemberName;
        boolean exported;
        boolean extensionHook;
        String annotationName;
        AnnotationMode annotationMode;
        boolean continueCall;
        String fullName;
    }

    @Value
    @Builder
    public static class ModuleCall {
        String callerModule;
        String callerMember;
        String calleeModule;
        String calleeMember;
        boolean logicalCall;
        String sourcePath;
    }

    @Value
    @Builder
    public static class ParsedCommonModule {
        String moduleName;
        String sourcePath;
        List<MemberDefinition> members;
        List<ModuleCall> calls;
    }

    @Value
    @Builder
    public static class ObjectExportUsage {
        String exportModule;
        String exportMember;
    }

    @Value
    @Builder
    public static class ParsedObjectUsages {
        DependencyCallerType objectType;
        String objectName;
        String sourcePath;
        List<ObjectExportUsage> usages;
    }
}
