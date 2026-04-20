package git.autoupdateservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.domain.RunStage;
import git.autoupdateservice.service.steps.RunPlan;
import git.autoupdateservice.service.steps.RunStepCommandService;
import git.autoupdateservice.service.steps.StepPlanLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class SmokeObjectListService {

    private static final Set<String> CONTROL_KEYS = Set.of(
            "Списки",
            "Новые",
            "Существующие",
            "ПеренестиДату",
            "Используется",
            "ОткрываемФормыНаКлиентеТестирования"
    );

    private final ObjectMapper objectMapper;
    private final RunnerProperties runnerProperties;
    private final StepPlanLoader stepPlanLoader;
    private final RunStepCommandService runStepCommandService;
    private final AuditLogService auditLogService;

    @Value("${app.test-objects.storage-file:}")
    private String configuredStorageFile;

    public Map<String, String> testConnectionSettings() {
        RunPlan plan = loadTestPlanOrNull();
        Map<String, String> settings = plan == null ? Map.of() : plan.getSettings();

        Map<String, String> result = new LinkedHashMap<>();
        result.put("ib-connection-repo", firstNonBlank(
                runStepCommandService.planValue(settings, "ibConnectionRepo", "ibConnectionrepo", "ib-connectionrepo", "ib-connection-repo"),
                runnerProperties.ibConnectionrepo(),
                runnerProperties.ibConnection()
        ));
        result.put("db-user", firstNonBlank(
                runStepCommandService.planValue(settings, "dbUser", "db-user"),
                runnerProperties.dbUser()
        ));
        result.put("db-password", firstNonBlank(
                runStepCommandService.planValue(settings, "dbPassword", "db-password", "db-pwd"),
                runnerProperties.dbPassword()
        ));
        return result;
    }

    public StoreResult storeLatestObjectList(String payloadText, String clientIp, String actor) throws IOException {
        RunPlan plan = loadTestPlanOrNull();
        Path file = resolveStorageFile(plan);

        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadText == null ? "" : payloadText);
        } catch (Exception e) {
            return new StoreResult(false, true, "Некорректный JSON: " + safe(e.getMessage()), file, Map.of());
        }

        if (payload == null || !payload.isObject()) {
            return new StoreResult(false, true, "Пустой или некорректный JSON", file, Map.of());
        }

        JsonNode errors = payload.path("Ошибки");
        boolean hasError = errors.path("ЕстьОшибка").asBoolean(false);
        String errorText = errors.path("ТекстОшибка").asText("");
        if (hasError) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "External object list contains error: " + safe(errorText),
                    "{\"path\":\"" + esc(file.toString()) + "\",\"saved\":false}",
                    clientIp,
                    actor,
                    null
            );
            return new StoreResult(false, true, errorText, file, Map.of());
        }

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);

        Map<DependencyCallerType, Set<String>> parsed = parseObjects(payload);
        auditLogService.info(
                LogType.STEP_FINISHED,
                "External object list saved",
                "{\"path\":\"" + esc(file.toString()) + "\",\"objects\":" + count(parsed) + "}",
                clientIp,
                actor,
                null
        );

        return new StoreResult(true, false, "", file, counts(parsed));
    }

    private RunPlan loadTestPlanOrNull() {
        try {
            return stepPlanLoader.loadPlan(RunStage.TEST);
        } catch (Exception e) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "Cannot load TEST plan for external object list API. Fallback settings will be used: " + safe(e.getMessage()),
                    "{}",
                    null,
                    "system",
                    null
            );
            return null;
        }
    }

    public Map<DependencyCallerType, Set<String>> loadLatestObjects(RunPlan plan) throws IOException {
        Path file = resolveStorageFile(plan);
        if (!Files.exists(file)) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "External object list file not found. Smoke config will be generated with default masks only.",
                    "{\"path\":\"" + esc(file.toString()) + "\"}",
                    null,
                    "system",
                    null
            );
            return new LinkedHashMap<>();
        }

        JsonNode payload = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (payload.path("Ошибки").path("ЕстьОшибка").asBoolean(false)) {
            auditLogService.warn(
                    LogType.STEP_FAILED,
                    "External object list file contains error flag. Smoke config will be generated with default masks only.",
                    "{\"path\":\"" + esc(file.toString()) + "\"}",
                    null,
                    "system",
                    null
            );
            return new LinkedHashMap<>();
        }
        return parseObjects(payload);
    }

    public Path resolveStorageFile(RunPlan plan) {
        String configured = firstNonBlank(
                plan == null ? null : plan.getObjectListFile(),
                setting(plan, "objectListFile"),
                setting(plan, "object-list-file"),
                setting(plan, "testObjectsFile"),
                setting(plan, "test-objects-file"),
                configuredStorageFile
        );

        if (!StringUtils.hasText(configured)) {
            configured = Path.of(runnerProperties.logDir(), "test-objects-latest.json").toString();
        }

        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        String loadedFrom = plan == null ? null : plan.getLoadedFrom();
        if (StringUtils.hasText(loadedFrom) && !loadedFrom.startsWith("classpath:")) {
            Path baseFile = Path.of(loadedFrom).toAbsolutePath().normalize();
            Path parent = baseFile.getParent();
            if (parent != null) {
                return parent.resolve(path).normalize();
            }
        }

        return path.toAbsolutePath().normalize();
    }

    private Map<DependencyCallerType, Set<String>> parseObjects(JsonNode payload) {
        Map<DependencyCallerType, Set<String>> result = new LinkedHashMap<>();
        collectSection(payload, "Справочники", DependencyCallerType.CATALOG, result);
        collectSection(payload, "Документы", DependencyCallerType.DOCUMENT, result);
        collectSection(payload, "Отчеты", DependencyCallerType.REPORT, result);
        collectSection(payload, "Обработки", DependencyCallerType.DATA_PROCESSOR, result);
        return result;
    }

    private void collectSection(JsonNode root, String sectionName, DependencyCallerType type, Map<DependencyCallerType, Set<String>> result) {
        JsonNode section = root == null ? null : root.get(sectionName);
        if (section == null || section.isNull() || section.isMissingNode() || section.isBoolean()) {
            return;
        }

        Set<String> values = result.computeIfAbsent(type, key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        collectObjectNames(section, values, true);
    }

    private void collectObjectNames(JsonNode node, Set<String> values, boolean sectionRoot) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isBoolean() || node.isNumber()) {
            return;
        }

        if (node.isTextual()) {
            addCandidate(values, node.asText());
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectObjectNames(child, values, false);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();

            if (CONTROL_KEYS.contains(name)) {
                collectObjectNames(value, values, false);
                continue;
            }

            if (!sectionRoot || value.isObject() || value.isArray() || value.isBoolean()) {
                addCandidate(values, name);
            }
            collectObjectNames(value, values, false);
        }
    }

    private void addCandidate(Set<String> values, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim();
        if (normalized.contains("*")) {
            return;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "false".equals(lower)) {
            return;
        }
        values.add(normalized);
    }

    private Map<String, Integer> counts(Map<DependencyCallerType, Set<String>> objects) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<DependencyCallerType, Set<String>> entry : objects.entrySet()) {
            result.put(entry.getKey().name(), entry.getValue().size());
        }
        return result;
    }

    private int count(Map<DependencyCallerType, Set<String>> objects) {
        return objects.values().stream().mapToInt(Set::size).sum();
    }

    private String setting(RunPlan plan, String key) {
        if (plan == null || plan.getSettings() == null) {
            return null;
        }
        return plan.getSettings().get(key);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() > 500 ? normalized.substring(0, 500) : normalized;
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

    public record StoreResult(
            boolean saved,
            boolean hasError,
            String errorText,
            Path path,
            Map<String, Integer> objectCounts
    ) {
    }
}
