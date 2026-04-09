package git.autoupdateservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.domain.ChangedObject;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.service.steps.RunPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class SmokeTestConfigService {

    private static final String SCHEMA_URL = "https://raw.githubusercontent.com/vanessa-opensource/vanessa-runner/develop/xunit-schema.json";
    private static final String DEFAULT_LOG_FILE = "$workspaceRoot/log-xunit.txt";

    private final ChangedObjectService changedObjectService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencySourceRootService dependencySourceRootService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Path generateForTesting(RunPlan plan, ExecutionRun run, Path workDir) throws IOException {
        Path outputFile = resolveOutputFile(plan, workDir);
        Map<DependencyCallerType, Set<String>> allObjects = collectAllObjects();
        Map<DependencyCallerType, Set<String>> changedObjects = collectChangedObjects(changedObjectService.findForTesting());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", SCHEMA_URL);
        root.put("ДелатьЛогВыполненияСценариевВТекстовыйФайл", true);
        root.put("ИмяФайлаЛогВыполненияСценариев", DEFAULT_LOG_FILE);
        root.put("Отладка", false);
        root.put("ДобавлятьИмяПользователяВПредставлениеТеста", true);
        root.put("smoke", buildSmokeSection(allObjects, changedObjects));

        Files.createDirectories(outputFile.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), root);

        auditLogService.info(
                LogType.STEP_FINISHED,
                "Smoke xUnit config generated",
                "{\"runId\":\"" + run.getId() + "\",\"path\":\"" + esc(outputFile.toString()) + "\"}",
                null,
                "system",
                run.getId()
        );

        return outputFile;
    }

    private Map<String, Object> buildSmokeSection(
            Map<DependencyCallerType, Set<String>> allObjects,
            Map<DependencyCallerType, Set<String>> changedObjects
    ) {
        Map<String, Object> smoke = new LinkedHashMap<>();
        smoke.put("Справочники", buildCatalogSection(allObjects, changedObjects));
        smoke.put("Документы", buildDocumentSection(allObjects, changedObjects));
        smoke.put("БизнесПроцессы", Map.of(
                "Списки", false,
                "Новые", false,
                "Существующие", false
        ));
        smoke.put("Отчеты", buildSimpleSection(DependencyCallerType.REPORT, allObjects, changedObjects, List.of("Удалить*")));
        smoke.put("Обработки", buildSimpleSection(DependencyCallerType.DATA_PROCESSOR, allObjects, changedObjects, List.of("Удалить*")));
        smoke.put("РегистрыСведений", Map.of(
                "Списки", false,
                "Новые", false,
                "Существующие", false
        ));
        smoke.put("РегистрыНакопления", Map.of(
                "Списки", false,
                "Новые", false,
                "Существующие", false
        ));
        smoke.put("Используется", true);
        smoke.put("ОткрываемФормыНаКлиентеТестирования", false);
        return smoke;
    }

    private Map<String, Object> buildCatalogSection(
            Map<DependencyCallerType, Set<String>> allObjects,
            Map<DependencyCallerType, Set<String>> changedObjects
    ) {
        List<String> exclusions = buildExclusions(
                DependencyCallerType.CATALOG,
                allObjects,
                changedObjects,
                List.of("Удалить*", "*ПрисоединенныеФайлы")
        );
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("Новые", false);
        section.put("Списки", exclusions);
        section.put("Существующие", exclusions);
        return section;
    }

    private Map<String, Object> buildDocumentSection(
            Map<DependencyCallerType, Set<String>> allObjects,
            Map<DependencyCallerType, Set<String>> changedObjects
    ) {
        List<String> exclusions = buildExclusions(
                DependencyCallerType.DOCUMENT,
                allObjects,
                changedObjects,
                List.of("Удалить*")
        );
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("Новые", false);
        section.put("Списки", exclusions);
        section.put("Существующие", exclusions);
        section.put("ПеренестиДату", false);
        return section;
    }

    private List<String> buildSimpleSection(
            DependencyCallerType type,
            Map<DependencyCallerType, Set<String>> allObjects,
            Map<DependencyCallerType, Set<String>> changedObjects,
            List<String> defaults
    ) {
        return buildExclusions(type, allObjects, changedObjects, defaults);
    }

    private List<String> buildExclusions(
            DependencyCallerType type,
            Map<DependencyCallerType, Set<String>> allObjects,
            Map<DependencyCallerType, Set<String>> changedObjects,
            List<String> defaults
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>(defaults);
        Set<String> all = allObjects.getOrDefault(type, Set.of());
        Set<String> changed = changedObjects.getOrDefault(type, Set.of());
        for (String objectName : all) {
            if (!containsIgnoreCase(changed, objectName)) {
                values.add(objectName);
            }
        }
        return new ArrayList<>(values);
    }

    private Map<DependencyCallerType, Set<String>> collectAllObjects() throws IOException {
        var baseRoot = codeSourceRootRepository.findFirstBySourceKindAndEnabledIsTrue(SourceKind.BASE)
                .orElseThrow(() -> new IllegalStateException("Не настроен активный источник основной конфигурации"));
        List<DependencySourceRootService.ScanRoot> scanRoots = dependencySourceRootService.collectScanRoots(baseRoot);
        var discovered = dependencySourceRootService.discoverBslFiles(scanRoots);

        Map<DependencyCallerType, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<DependencySourceRootService.ScanRoot, List<Path>> entry : discovered.objectFilesByRoot().entrySet()) {
            DependencySourceRootService.ScanRoot root = entry.getKey();
            for (Path file : entry.getValue()) {
                DependencySourceRootService.ObjectRef ref = dependencySourceRootService.determineObjectRef(root.path(), file);
                if (ref == null || ref.objectType() == null || !StringUtils.hasText(ref.objectName())) {
                    continue;
                }
                if (!isSupportedType(ref.objectType())) {
                    continue;
                }
                result.computeIfAbsent(ref.objectType(), key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .add(ref.objectName().trim());
            }
        }
        return result;
    }

    private Map<DependencyCallerType, Set<String>> collectChangedObjects(Collection<ChangedObject> rows) {
        Map<DependencyCallerType, Set<String>> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (ChangedObject row : rows) {
            if (row == null || row.getObjectType() == null || !StringUtils.hasText(row.getObjectName())) {
                continue;
            }
            if (!isSupportedType(row.getObjectType())) {
                continue;
            }
            result.computeIfAbsent(row.getObjectType(), key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(row.getObjectName().trim());
        }
        return result;
    }

    private Path resolveOutputFile(RunPlan plan, Path workDir) {
        String configured = plan == null ? null : plan.getXunitConfigFile();
        if (!StringUtils.hasText(configured) && plan != null && plan.getSettings() != null) {
            configured = firstNonBlank(
                    plan.getSettings().get("xunitConfigFile"),
                    plan.getSettings().get("xunit-config-file"),
                    plan.getSettings().get("smokeConfigFile"),
                    plan.getSettings().get("smoke-config-file")
            );
        }
        if (!StringUtils.hasText(configured)) {
            return workDir.resolve("smoke-xunit.json").toAbsolutePath().normalize();
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = workDir.resolve(configured);
        }
        return path.toAbsolutePath().normalize();
    }

    private boolean isSupportedType(DependencyCallerType type) {
        return type == DependencyCallerType.CATALOG
                || type == DependencyCallerType.DOCUMENT
                || type == DependencyCallerType.REPORT
                || type == DependencyCallerType.DATA_PROCESSOR;
    }

    private boolean containsIgnoreCase(Set<String> values, String target) {
        if (values == null || values.isEmpty() || target == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
