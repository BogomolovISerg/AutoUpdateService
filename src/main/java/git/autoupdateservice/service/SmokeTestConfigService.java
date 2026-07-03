package git.autoupdateservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.ChangedObject;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.ExecutionRun;
import git.autoupdateservice.domain.LogType;
import git.autoupdateservice.service.steps.RunPlan;
import git.autoupdateservice.service.steps.RunStepCommandService;
import git.autoupdateservice.util.JsonPrettyPrinters;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SmokeTestConfigService {

    private static final String SCHEMA_URL = "https://raw.githubusercontent.com/vanessa-opensource/vanessa-runner/develop/xunit-schema.json";
    private static final String ENV_SCHEMA_URL = "https://raw.githubusercontent.com/vanessa-opensource/vanessa-runner/develop/vanessa-runner-schema.json";
    private static final String DEFAULT_LOG_FILE = "$workspaceRoot/log-xunit.txt";
    private static final String DEFAULT_ADDITIONAL = "/DisplayAllFunctions /Lru /iTaxi";
    private static final Set<String> NON_BLOCKING_ALLURE_FAILURE_MESSAGES = Set.of(
            "Данная форма не предназначена для непосредственного открытия.",
            "Для открытия формы необходимо передать параметры.",
            "Не предусмотрено непосредственное открытие формы обработки.",
            "Открытие отчета предусмотрено только из документов.",
            "Отчет не предназначен для неконтекстного использования. Открывайте отчет из формы списка или элемента сегмента.",
            "Предусмотрено открытие обработки только из документов.",
            "Предусмотрено открытие обработки только из форм объектов.",
            "Форма не предназначена для непосредственного открытия."
    );

    private final ChangedObjectService changedObjectService;
    private final SmokeObjectListService smokeObjectListService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final RunnerProperties runnerProperties;
    private final RunStepCommandService runStepCommandService;

    public enum SmokeTestStatus {
        SUCCESS("0", "Тестирование завершено без ошибок."),
        FAILED("1", "Тестирование завершено с ошибками."),
        NOT_STARTED("2", "Тестирование не было запущено."),
        RUNNING("3", "Тестирование ещё не закончилось."),
        MISSING("", "Тестирование не было запущено."),
        UNKNOWN("", "Статус тестирования не распознан.");

        private final String code;
        private final String description;

        SmokeTestStatus(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String code() {
            return code;
        }

        public String description() {
            return description;
        }

        public boolean blocksProduction() {
            return this == FAILED;
        }

        public static SmokeTestStatus fromStoredValue(String value) {
            if (!StringUtils.hasText(value)) {
                return MISSING;
            }
            String normalized = value.trim();
            return switch (normalized) {
                case "0" -> SUCCESS;
                case "1" -> FAILED;
                case "2" -> NOT_STARTED;
                case "3" -> RUNNING;
                default -> UNKNOWN;
            };
        }
    }

    public record SmokeTestStatusInfo(Path path, SmokeTestStatus status, String rawValue) {
    }

    private record AllureFailureAnalysis(
            Path testCasesDir,
            int totalCases,
            int passedOrSkipped,
            int allowedFailures,
            int blockingFailures,
            int unreadableFiles
    ) {
        boolean canTreatAsSuccess() {
            return totalCases > 0
                    && allowedFailures > 0
                    && blockingFailures == 0
                    && unreadableFiles == 0;
        }
    }

    public Path prepareOutputFile(RunPlan plan, Path workDir) {
        Path outputFile = resolveOutputFile(plan, workDir);
        publishOutputFileTokens(plan, outputFile);
        publishTestResultFileTokens(plan);
        return outputFile;
    }

    public boolean hasConfiguredOutputFile(RunPlan plan) {
        if (plan == null) {
            return false;
        }
        if (StringUtils.hasText(plan.getXunitConfigFile())) {
            return true;
        }
        if (plan.getSettings() == null) {
            return false;
        }
        return StringUtils.hasText(firstNonBlank(
                plan.getSettings().get("xunitConfigFile"),
                plan.getSettings().get("xunit-config-file"),
                plan.getSettings().get("smokeConfigFile"),
                plan.getSettings().get("smoke-config-file")
        ));
    }

    public Path ensureGeneratedForTesting(RunPlan plan, ExecutionRun run, Path workDir) throws IOException {
        Path outputFile = prepareOutputFile(plan, workDir);
        if (Files.exists(outputFile)) {
            return outputFile;
        }
        return generateForTesting(plan, run, workDir);
    }

    @Transactional(readOnly = true)
    public Path generateForTesting(RunPlan plan, ExecutionRun run, Path workDir) throws IOException {
        Path outputFile = prepareOutputFile(plan, workDir);
        Path envFile = resolveEnvFile(plan, workDir, outputFile);
        Map<DependencyCallerType, Set<String>> allObjects = smokeObjectListService.loadLatestObjects(plan);
        Map<DependencyCallerType, Set<String>> changedObjects = collectChangedObjects(changedObjectService.findForTesting());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", SCHEMA_URL);
        root.put("ДелатьЛогВыполненияСценариевВТекстовыйФайл", true);
        root.put("ИмяФайлаЛогВыполненияСценариев", DEFAULT_LOG_FILE);
        root.put("Отладка", false);
        root.put("ДобавлятьИмяПользователяВПредставлениеТеста", false);
        root.put("smoke", buildSmokeSection(allObjects, changedObjects));

        Files.createDirectories(outputFile.getParent());
        objectMapper.writer(JsonPrettyPrinters.multilineArrays()).writeValue(outputFile.toFile(), root);
        writeEnvFile(plan, run, workDir, outputFile, envFile);

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

    private void writeEnvFile(RunPlan plan, ExecutionRun run, Path workDir, Path smokeConfigFile, Path envFile) throws IOException {
        Path outputDir = smokeConfigFile.getParent() == null ? workDir : smokeConfigFile.getParent();
        Path reportsDir = outputDir.resolve("out").normalize();
        Files.createDirectories(reportsDir);
        if (envFile.getParent() != null) {
            Files.createDirectories(envFile.getParent());
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", ENV_SCHEMA_URL);

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("--ibconnection", resolveSetting(plan, "ibConnectionRepo", "ibConnectionrepo", "ib-connectionrepo", "ib-connection-repo"));
        defaults.put("--db-user", resolveSetting(plan, "dbUser", "db-user"));
        defaults.put("--db-pwd", resolveSetting(plan, "dbPassword", "db-password", "db-pwd"));
        defaults.put("--additional", resolveSettingOrDefault(plan, DEFAULT_ADDITIONAL, "additional", "runner.additional"));
        defaults.put("--workspace", ".");
        defaults.put("--ordinaryapp", "0");
        root.put("default", defaults);

        Map<String, Object> xunit = new LinkedHashMap<>();
        xunit.put("testsPath", resolveTestsPath(plan, run, workDir, outputDir));
        xunit.put("--xddConfig", relativeToWorkDir(workDir, smokeConfigFile));
        xunit.put("--reportsxunit",
                "ГенераторОтчетаJUnitXML{" + relativeToWorkDir(workDir, reportsDir.resolve("junit.xml"))
                        + "};ГенераторОтчетаAllureXMLВерсия2{" + relativeToWorkDir(workDir, reportsDir.resolve("allure.xml")) + "}");
        xunit.put("--xddExitCodePath", resolveTestResultFile(plan).toString());
        root.put("xunit", xunit);

        objectMapper.writer(JsonPrettyPrinters.multilineArrays()).writeValue(envFile.toFile(), root);

        auditLogService.info(
                LogType.STEP_FINISHED,
                "Vanessa runner env config generated",
                "{\"runId\":\"" + run.getId() + "\",\"path\":\"" + esc(envFile.toString()) + "\"}",
                null,
                "system",
                run.getId()
        );
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
        smoke.put("ОткрываемФормыНаКлиентеТестирования", true);
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

    private void publishOutputFileTokens(RunPlan plan, Path outputFile) {
        if (plan == null || plan.getSettings() == null || outputFile == null) {
            return;
        }
        String value = outputFile.toString();
        plan.getSettings().put("xunitConfigFile", value);
        plan.getSettings().put("xunit-config-file", value);
        plan.getSettings().put("smokeConfigFile", value);
        plan.getSettings().put("smoke-config-file", value);
        Path envFile = resolveEnvFile(plan, outputFile.getParent() == null ? outputFile.toAbsolutePath().getParent() : outputFile.getParent(), outputFile);
        String envValue = envFile.toString();
        plan.getSettings().put("envConfigFile", envValue);
        plan.getSettings().put("env-config-file", envValue);
        plan.getSettings().put("envFile", envValue);
        plan.getSettings().put("env-file", envValue);
    }

    public Path publishTestResultFileTokens(RunPlan plan) {
        Path path = resolveTestResultFile(plan);
        if (plan == null || plan.getSettings() == null) {
            return path;
        }
        String value = path.toString();
        plan.getSettings().put("testResultFile", value);
        plan.getSettings().put("test-result-file", value);
        plan.getSettings().put("xddExitCodePath", value);
        plan.getSettings().put("xdd-exit-code-path", value);
        return path;
    }

    private Path resolveEnvFile(RunPlan plan, Path workDir, Path outputFile) {
        String configured = null;
        if (plan != null && plan.getSettings() != null) {
            configured = firstNonBlank(
                    plan.getSettings().get("envConfigFile"),
                    plan.getSettings().get("env-config-file"),
                    plan.getSettings().get("envFile"),
                    plan.getSettings().get("env-file")
            );
        }
        if (!StringUtils.hasText(configured)) {
            Path outputDir = outputFile != null && outputFile.getParent() != null
                    ? outputFile.getParent()
                    : workDir.resolve("generated");
            return outputDir.resolve("env.json").toAbsolutePath().normalize();
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = workDir.resolve(configured);
        }
        return path.toAbsolutePath().normalize();
    }

    public Path resolveTestResultFile(RunPlan plan) {
        String configured = firstNonBlank(
                plan == null ? null : plan.getTestResultFile(),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("testResultFile"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("test-result-file"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("xddExitCodePath"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("xdd-exit-code-path")
        );
        Path serviceDir = resolveServiceDir(plan);
        if (!StringUtils.hasText(configured)) {
            return serviceDir.resolve("generated").resolve("xddExitCodePath.txt").toAbsolutePath().normalize();
        }

        String rendered = runStepCommandService.render(configured, buildTemplateContext(plan, null, null, serviceDir));

        Path path = Path.of(rendered);
        if (!path.isAbsolute()) {
            path = serviceDir.resolve(rendered);
        }
        return path.toAbsolutePath().normalize();
    }

    public boolean isSmokeTestsEnabled(RunPlan plan) {
        if (plan == null || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return false;
        }
        for (var step : plan.getSteps()) {
            if (step == null || !step.isEnabled()) {
                continue;
            }
            String code = step.getCode();
            if (!StringUtils.hasText(code)) {
                continue;
            }
            String normalized = code.trim().toLowerCase();
            if ("smoke_tests".equals(normalized) || "smoketests".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public SmokeTestStatusInfo prepareStatusFileForTestRun(RunPlan plan) throws IOException {
        Path path = publishTestResultFileTokens(plan);
        if (!isSmokeTestsEnabled(plan)) {
            Files.deleteIfExists(path);
            return new SmokeTestStatusInfo(path, SmokeTestStatus.NOT_STARTED, null);
        }
        writeStatus(path, SmokeTestStatus.NOT_STARTED.code());
        return new SmokeTestStatusInfo(path, SmokeTestStatus.NOT_STARTED, SmokeTestStatus.NOT_STARTED.code());
    }

    public SmokeTestStatusInfo markSmokeTestsStarted(RunPlan plan) throws IOException {
        Path path = publishTestResultFileTokens(plan);
        writeStatus(path, SmokeTestStatus.RUNNING.code());
        return new SmokeTestStatusInfo(path, SmokeTestStatus.RUNNING, SmokeTestStatus.RUNNING.code());
    }

    public SmokeTestStatusInfo markSmokeTestsFailed(RunPlan plan) throws IOException {
        Path path = publishTestResultFileTokens(plan);
        writeStatus(path, SmokeTestStatus.FAILED.code());
        return new SmokeTestStatusInfo(path, SmokeTestStatus.FAILED, SmokeTestStatus.FAILED.code());
    }

    public SmokeTestStatusInfo markSmokeTestsSucceeded(RunPlan plan) throws IOException {
        Path path = publishTestResultFileTokens(plan);
        writeStatus(path, SmokeTestStatus.SUCCESS.code());
        return new SmokeTestStatusInfo(path, SmokeTestStatus.SUCCESS, SmokeTestStatus.SUCCESS.code());
    }

    public SmokeTestStatusInfo readStatusInfo(RunPlan plan) throws IOException {
        Path path = resolveTestResultFile(plan);
        if (!Files.exists(path)) {
            return new SmokeTestStatusInfo(path, SmokeTestStatus.MISSING, null);
        }
        String rawValue = Files.readString(path).trim();
        return new SmokeTestStatusInfo(path, SmokeTestStatus.fromStoredValue(rawValue), rawValue);
    }

    public SmokeTestStatusInfo acceptNonBlockingAllureFailuresIfApplicable(RunPlan plan, ExecutionRun run) throws IOException {
        SmokeTestStatusInfo statusInfo = readStatusInfo(plan);
        if (statusInfo.status() != SmokeTestStatus.FAILED || run == null || run.getId() == null) {
            return statusInfo;
        }

        AllureFailureAnalysis analysis = analyzeAllureFailures(run.getId());
        if (!analysis.canTreatAsSuccess()) {
            auditLogService.info(
                    LogType.STEP_FINISHED,
                    "Allure test cases analyzed. Smoke result remains FAILED.",
                    "{\"runId\":\"" + run.getId()
                            + "\",\"testCases\":" + analysis.totalCases()
                            + ",\"allowedFailures\":" + analysis.allowedFailures()
                            + ",\"blockingFailures\":" + analysis.blockingFailures()
                            + ",\"unreadableFiles\":" + analysis.unreadableFiles()
                            + ",\"path\":\"" + esc(analysis.testCasesDir().toString()) + "\"}",
                    null,
                    "system",
                    run.getId()
            );
            return statusInfo;
        }

        writeStatus(statusInfo.path(), SmokeTestStatus.SUCCESS.code());
        auditLogService.warn(
                LogType.STEP_FINISHED,
                "Smoke result adjusted to SUCCESS because Allure contains only non-blocking form opening failures.",
                "{\"runId\":\"" + run.getId()
                        + "\",\"testCases\":" + analysis.totalCases()
                        + ",\"allowedFailures\":" + analysis.allowedFailures()
                        + ",\"path\":\"" + esc(statusInfo.path().toString()) + "\"}",
                null,
                "system",
                run.getId()
        );
        return new SmokeTestStatusInfo(statusInfo.path(), SmokeTestStatus.SUCCESS, SmokeTestStatus.SUCCESS.code());
    }

    private AllureFailureAnalysis analyzeAllureFailures(UUID runId) {
        Path testCasesDir = resolveAllureTestCasesDir(runId);
        if (!Files.isDirectory(testCasesDir)) {
            return new AllureFailureAnalysis(testCasesDir, 0, 0, 0, 0, 0);
        }

        int totalCases = 0;
        int passedOrSkipped = 0;
        int allowedFailures = 0;
        int blockingFailures = 0;
        int unreadableFiles = 0;

        try (Stream<Path> files = Files.list(testCasesDir)) {
            List<Path> jsonFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();

            for (Path file : jsonFiles) {
                totalCases++;
                try {
                    JsonNode root = objectMapper.readTree(file.toFile());
                    String status = text(root, "status").trim().toLowerCase();
                    if ("passed".equals(status) || "skipped".equals(status)) {
                        passedOrSkipped++;
                        continue;
                    }

                    String statusMessage = text(root, "statusMessage").trim();
                    if ("failed".equals(status) && NON_BLOCKING_ALLURE_FAILURE_MESSAGES.contains(statusMessage)) {
                        allowedFailures++;
                    } else {
                        blockingFailures++;
                    }
                } catch (Exception e) {
                    unreadableFiles++;
                }
            }
        } catch (IOException e) {
            return new AllureFailureAnalysis(testCasesDir, totalCases, passedOrSkipped, allowedFailures, blockingFailures, unreadableFiles + 1);
        }

        return new AllureFailureAnalysis(testCasesDir, totalCases, passedOrSkipped, allowedFailures, blockingFailures, unreadableFiles);
    }

    private Path resolveAllureTestCasesDir(UUID runId) {
        String logDir = runnerProperties.logDir();
        if (!StringUtils.hasText(logDir)) {
            logDir = "./runner-logs";
        }
        return Path.of(logDir)
                .toAbsolutePath()
                .normalize()
                .resolve("run-" + runId)
                .resolve("allure")
                .resolve("data")
                .resolve("test-cases")
                .normalize();
    }

    private String text(JsonNode root, String fieldName) {
        if (root == null || !root.has(fieldName) || root.get(fieldName).isNull()) {
            return "";
        }
        return root.get(fieldName).asText("");
    }

    private String resolveTestsPath(RunPlan plan, ExecutionRun run, Path workDir, Path fallbackDir) {
        String configured = firstNonBlank(
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("testsPath"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("tests-path"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("xunitTestsPath"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("xunit-tests-path"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("testSourcesPath"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("test-sources-path")
        );
        if (!StringUtils.hasText(configured)) {
            return relativeToWorkDir(workDir, fallbackDir);
        }

        return runStepCommandService.render(configured, buildTemplateContext(plan, run, workDir, resolveServiceDir(plan)));
    }

    private String resolveSetting(RunPlan plan, String... aliases) {
        return resolveSettingOrDefault(plan, "", aliases);
    }

    private String resolveSettingOrDefault(RunPlan plan, String fallback, String... aliases) {
        String value = runStepCommandService.planValue(plan == null ? null : plan.getSettings(), aliases);
        if (StringUtils.hasText(value)) {
            return value;
        }
        for (String alias : aliases) {
            switch (alias) {
                case "ibConnectionRepo", "ibConnectionrepo", "ib-connectionrepo", "ib-connection-repo" -> {
                    value = firstNonBlank(runnerProperties.ibConnectionrepo(), runnerProperties.ibConnection());
                }
                case "dbUser", "db-user" -> value = runnerProperties.dbUser();
                case "dbPassword", "db-password", "db-pwd" -> value = runnerProperties.dbPassword();
                default -> {
                }
            }
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return fallback;
    }

    private Path resolveServiceDir(RunPlan plan) {
        String configured = firstNonBlank(
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("service-dir"),
                plan == null || plan.getSettings() == null ? null : plan.getSettings().get("serviceDir")
        );
        if (!StringUtils.hasText(configured)) {
            return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }

        String rendered = runStepCommandService.render(configured, buildTemplateContext(plan, null, null, null));
        Path path = Path.of(rendered);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private Map<String, String> buildTemplateContext(RunPlan plan, ExecutionRun run, Path workDir, Path serviceDir) {
        Map<String, String> context = new LinkedHashMap<>();
        if (plan != null && plan.getSettings() != null) {
            context.putAll(plan.getSettings());
        }

        Path resolvedServiceDir = serviceDir == null
                ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                : serviceDir.toAbsolutePath().normalize();
        String serviceDirValue = resolvedServiceDir.toString();

        context.putIfAbsent("service-dir", serviceDirValue);
        context.putIfAbsent("serviceDir", serviceDirValue);
        context.putIfAbsent("generated-dir", resolvedServiceDir.resolve("generated").toString());
        context.putIfAbsent("generatedDir", resolvedServiceDir.resolve("generated").toString());
        context.putIfAbsent("log-dir-root", runnerProperties.logDir() == null ? "" : runnerProperties.logDir());
        context.putIfAbsent("logDirRoot", runnerProperties.logDir() == null ? "" : runnerProperties.logDir());

        if (workDir != null) {
            String workDirValue = workDir.toAbsolutePath().normalize().toString();
            context.put("runDir", workDirValue);
            context.put("workDir", workDirValue);
            context.put("log-dir", workDirValue);
            context.put("logDir", workDirValue);
        }
        if (run != null) {
            context.put("runId", String.valueOf(run.getId()));
        }
        return context;
    }

    private void writeStatus(Path path, String value) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, value == null ? "" : value);
    }

    private String relativeToWorkDir(Path workDir, Path target) {
        Path base = workDir.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        try {
            Path relative = base.relativize(normalizedTarget);
            String value = relative.toString().replace('\\', '/');
            if (value.isBlank()) {
                return ".";
            }
            return value.startsWith(".") ? value : "./" + value;
        } catch (Exception e) {
            return normalizedTarget.toString().replace('\\', '/');
        }
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
