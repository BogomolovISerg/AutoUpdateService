package git.autoupdateservice.service;

import git.autoupdateservice.domain.DependencyCallerType;
import lombok.Builder;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
          //  Pattern.compile("(?iu)\\bЭкспорт\\b");
           Pattern.compile("(?iu)(?<!\\p{L})Экспорт(?!\\p{L})");

    private static final Set<String> RESERVED_WORDS = Set.of(
            "если", "тогда", "иначе", "иначеесли", "конецесли",
            "для", "каждого", "из", "по", "цикл", "конеццикла",
            "пока", "возврат", "новый", "попытка", "исключение",
            "конецпопытки", "прервать", "продолжить", "выполнить", "вызватьисключение"
    );

    private final OneCNameDecoder oneCNameDecoder;
    private static final Logger log = LoggerFactory.getLogger(BslDependencyParser.class);

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

        boolean debugModule = owner != null && "CRMЛокализация".equals(owner.callerName());

        if (owner == null || owner.callerType() != DependencyCallerType.COMMON_MODULE) {
            return null;
        }

        List<MemberBlock> memberBlocks = extractMemberBlocks(fileText);
        Map<String, String> localMembersByLower = new HashMap<>();
        List<MemberDefinition> members = new ArrayList<>();
        for (MemberBlock block : memberBlocks) {

            if (debugModule) {
                String preview = block.body() == null ? "" : block.body().replace('\n', ' ');
                if (preview.length() > 300) {
                    preview = preview.substring(0, 300);
                }

             /*   log.info("[BSL-BLOCK-CHECK] module={} | member={} | exported={} | bodyLength={} | bodyPreview={}",
                        owner.callerName(),
                        block.memberName(),
                        block.exported(),
                        block.body() == null ? 0 : block.body().length(),
                        preview);*/
            }

            localMembersByLower.put(block.memberName().toLowerCase(Locale.ROOT), block.memberName());
            members.add(MemberDefinition.builder()
                    .moduleName(owner.callerName())
                    .memberName(block.memberName())
                    .exported(block.exported())
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

      /*  log.info("[BSL-PARSER-CHECK] module={} | members={} | exports={} | calls={}",
                owner.callerName(),
                members.stream().map(MemberDefinition::getMemberName).toList(),
                members.stream().filter(MemberDefinition::isExported).map(MemberDefinition::getMemberName).toList(),
                calls.size());*/

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
        int debugCount = 0;
        Matcher matcher = QUALIFIED_CALL.matcher(stripped);
        List<ObjectExportUsage> usages = new ArrayList<>();
        Set<String> dedup = new LinkedHashSet<>();

        while (matcher.find()) {
            String module = matcher.group(1);
            String member = matcher.group(2);

            /*if (debugCount < 20) {
                log.info("[BSL-OBJECT-CALL] object={} | path={} | call={}.{}",
                        owner.callerName(), decodedRel, module, member);
                debugCount++;
            }*/

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

            String headerText = header.toString();
            boolean exported = EXPORT_WORD.matcher(headerText).find();

          /*  log.info("[BSL-EXPORT-CHECK] member={} | exported={} | header={}",
                    memberName,
                    exported,
                    headerText.replace('\n', ' '));*/

            StringBuilder body = new StringBuilder();
            j++;

            for (; j < lines.length; j++) {
                String bodyLine = stripComment(lines[j]);
                Matcher end = END_MEMBER.matcher(bodyLine);
                if (end.find()) {
                    result.add(new MemberBlock(memberName, body.toString(), exported));
                    i = j;
                    break;
                }

                body.append(bodyLine).append('\n');
            }
        }

        return result;
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

           /* if ("CRMЛокализация".equals(moduleName)) {
                log.info("[BSL-QUALIFIED-CANDIDATE] module={} | currentMember={} | left={} | right={}",
                        moduleName, currentMember, left, right);
            }*/

            if (calleeModule == null) {
               /* if ("CRMЛокализация".equals(moduleName)) {
                    log.info("[BSL-QUALIFIED-SKIP] module={} | currentMember={} | left={} | right={} | reason=module-not-in-knownCommonModules",
                            moduleName, currentMember, left, right);
                }*/

                continue;
            }
            /*if ("CRMЛокализация".equals(moduleName)) {
                log.info("[BSL-QUALIFIED-ACCEPT] module={} | currentMember={} | calleeModule={} | calleeMember={}",
                        moduleName, currentMember, calleeModule, right);
            }*/
            addCall(decodedRel, moduleName, currentMember, calleeModule, right, dedup, calls);
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

          /*  if ("CRMЛокализация".equals(moduleName)) {
                log.info("[BSL-LOCAL-CANDIDATE] module={} | currentMember={} | candidate={}",
                        moduleName, currentMember, candidate);
            }*/

            if (RESERVED_WORDS.contains(candidateLower) || excludedLower.contains(candidateLower)) {
               /* if ("CRMЛокализация".equals(moduleName)) {
                    log.info("[BSL-LOCAL-SKIP] module={} | currentMember={} | candidate={} | reason=reserved-or-excluded",
                            moduleName, currentMember, candidate);
                }*/
                continue;
            }

            String canonicalMember = localMembersByLower.get(candidateLower);
            if (canonicalMember == null) {
                /*if ("CRMЛокализация".equals(moduleName)) {
                    log.info("[BSL-LOCAL-SKIP] module={} | currentMember={} | candidate={} | reason=not-found-in-local-members",
                            moduleName, currentMember, candidate);
                }*/
                continue;
            }
            if (currentMember != null && candidateLower.equals(currentMember.toLowerCase(Locale.ROOT))) {
                /*if ("CRMЛокализация".equals(moduleName)) {
                    log.info("[BSL-LOCAL-SKIP] module={} | currentMember={} | candidate={} | reason=self-call",
                            moduleName, currentMember, candidate);
                }*/
                continue;
            }

            /*if ("CRMЛокализация".equals(moduleName)) {
                log.info("[BSL-LOCAL-ACCEPT] module={} | currentMember={} | candidate={} | canonical={}",
                        moduleName, currentMember, candidate, canonicalMember);
            }*/

            addCall(decodedRel, moduleName, currentMember, moduleName, canonicalMember, dedup, calls);
        }
    }

    private void addCall(
            String decodedRel,
            String callerModule,
            String callerMember,
            String calleeModule,
            String calleeMember,
            Set<String> dedup,
            List<ModuleCall> calls
    ) {
        String key = callerModule + "|" + nvl(callerMember) + "|" + calleeModule + "|" + nvl(calleeMember) + "|" + decodedRel;
        boolean debugModule = "CRMЛокализация".equals(callerModule);
        /*if (debugModule) {
            log.info("[BSL-ADD-CALL-TRY] callerModule={} | callerMember={} | calleeModule={} | calleeMember={} | key={}",
                    callerModule,
                    callerMember,
                    calleeModule,
                    calleeMember,
                    key);
        }*/

        if (!dedup.add(key)) {
           /* if (debugModule) {
                log.info("[BSL-ADD-CALL-SKIP] callerModule={} | callerMember={} | calleeModule={} | calleeMember={} | reason=duplicate",
                        callerModule,
                        callerMember,
                        calleeModule,
                        calleeMember);
            }*/
            return;
        }

        calls.add(ModuleCall.builder()
                .callerModule(callerModule)
                .callerMember(callerMember)
                .calleeModule(calleeModule)
                .calleeMember(calleeMember)
                .sourcePath(decodedRel)
                .build());
        /*if (debugModule) {
            log.info("[BSL-ADD-CALL-ACCEPT] callerModule={} | callerMember={} | calleeModule={} | calleeMember={}",
                    callerModule,
                    callerMember,
                    calleeModule,
                    calleeMember);
        }*/
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

    private record ParsedOwner(DependencyCallerType callerType, String callerName) {}
    private record MemberBlock(String memberName, String body, boolean exported) {}

    @Value
    @Builder
    public static class MemberDefinition {
        String moduleName;
        String memberName;
        boolean exported;
        String fullName;
    }

    @Value
    @Builder
    public static class ModuleCall {
        String callerModule;
        String callerMember;
        String calleeModule;
        String calleeMember;
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
