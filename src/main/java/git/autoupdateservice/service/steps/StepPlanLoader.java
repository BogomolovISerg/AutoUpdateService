package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.config.RunnerProperties;
import git.autoupdateservice.domain.RunStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class StepPlanLoader {

    private static final String DEFAULT_TEST_CLASSPATH = "runner-test.default.json";
    private static final String DEFAULT_PRODUCTION_CLASSPATH = "runner-prod.default.json";

    private final RunnerProperties runnerProperties;
    private final ObjectMapper objectMapper;

    public record ExtensionPlanSpec(String extensionName, String extFile, RunPlan plan) {
    }

    public RunPlan loadPlan(RunStage stage) {
        String src = resolveSource(stage);
        return loadPlanFromSource(src, stage + " plan");
    }

    public Optional<RunPlan> loadExtensionPlan(RunPlan basePlan, String extensionName, String extFile) {
        if (basePlan == null || !StringUtils.hasText(basePlan.getExtensionPlanFilePattern())) {
            return Optional.empty();
        }

        String rendered = renderExtensionSource(basePlan.getExtensionPlanFilePattern(), extensionName, extFile);
        String resolved = resolveRelativeSource(basePlan.getLoadedFrom(), rendered);
        RunPlan plan = loadPlanFromSource(resolved, "extension plan for " + extensionName);
        return Optional.of(mergeExtensionPlan(basePlan, plan));
    }

    public List<ExtensionPlanSpec> loadDiscoveredExtensionPlans(RunPlan basePlan) {
        if (basePlan == null || !StringUtils.hasText(basePlan.getExtensionPlanFilePattern())) {
            return List.of();
        }

        String resolvedTemplate = resolveRelativeSource(basePlan.getLoadedFrom(), basePlan.getExtensionPlanFilePattern());
        if (!StringUtils.hasText(resolvedTemplate) || resolvedTemplate.startsWith("classpath:")) {
            return List.of();
        }

        Path templatePath = Path.of(resolvedTemplate).toAbsolutePath().normalize();
        Path directory = templatePath.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        String fileTemplate = templatePath.getFileName().toString();
        String fileGlob = fileTemplate
                .replace("{{extFile}}", "*")
                .replace("{{ext}}", "*");

        PathMatcher matcher = directory.getFileSystem().getPathMatcher("glob:" + fileGlob);
        List<ExtensionPlanSpec> discovered = new ArrayList<>();

        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(path.getFileName()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> discovered.add(loadDiscoveredExtensionPlan(basePlan, resolvedTemplate, path)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list extension plans in directory " + directory + ": " + e.getMessage(), e);
        }

        return discovered;
    }

    private RunPlan loadPlanFromSource(String src, String label) {
        try {
            String json;

            if (src.startsWith("classpath:")) {
                String cp = src.substring("classpath:".length());
                json = readClasspath(cp.startsWith("/") ? cp.substring(1) : cp);
                log.info("Loaded {} from classpath resource: {}", label, src);
            } else {
                Path p = Path.of(src).toAbsolutePath().normalize();
                if (!Files.exists(p)) {
                    throw new IllegalStateException("Plan file not found: " + p);
                }
                json = Files.readString(p, StandardCharsets.UTF_8);
                log.info("Loaded {} from external file: {}", label, p);
            }
            return parse(json, src);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot load " + label + " from configured source: " + src + ". " +
                            "If this is an external file, ensure it exists and is saved in UTF-8. " +
                            "Error: " + e.getMessage(), e
            );
        }
    }

    private String resolveSource(RunStage stage) {
        String configured = switch (stage) {
            case TEST -> firstNonBlank(runnerProperties.testPlanFile(), runnerProperties.stepsFile());
            case PRODUCTION -> firstNonBlank(runnerProperties.productionPlanFile(), runnerProperties.stepsFile());
        };
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String legacy = runnerProperties.stepsFile();
        if (legacy != null && !legacy.isBlank()) {
            return legacy.trim();
        }
        return "classpath:" + switch (stage) {
            case TEST -> DEFAULT_TEST_CLASSPATH;
            case PRODUCTION -> DEFAULT_PRODUCTION_CLASSPATH;
        };
    }

    private RunPlan parse(String json, String source) {
        try {
            String normalized = json == null ? "" : json.trim();
            if (normalized.startsWith("[")) {
                RunPlan legacy = new RunPlan();
                legacy.setSteps(objectMapper.readValue(normalized, new TypeReference<List<RunStepDef>>() {}));
                legacy.setLoadedFrom(source);
                return legacy;
            }
            RunPlan plan = objectMapper.readValue(normalized, RunPlan.class);
            if (plan.getSteps() == null) {
                plan.setSteps(List.of());
            }
            if (plan.getSettings() == null) {
                plan.setSettings(new LinkedHashMap<>());
            } else if (!(plan.getSettings() instanceof LinkedHashMap)) {
                plan.setSettings(new LinkedHashMap<>(plan.getSettings()));
            }
            plan.setLoadedFrom(source);
            return plan;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid run plan JSON. Source=" + source + ". Error=" + e.getMessage(), e);
        }
    }

    private RunPlan mergeExtensionPlan(RunPlan basePlan, RunPlan extensionPlan) {
        RunPlan merged = new RunPlan();
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        if (basePlan.getSettings() != null) {
            settings.putAll(basePlan.getSettings());
        }
        if (extensionPlan.getSettings() != null) {
            settings.putAll(extensionPlan.getSettings());
        }
        merged.setSettings(settings);
        merged.setTestResultFile(extensionPlan.getTestResultFile());
        merged.setXunitConfigFile(extensionPlan.getXunitConfigFile());
        merged.setExtensionPlanFilePattern(extensionPlan.getExtensionPlanFilePattern());
        merged.setExt(firstNonBlank(extensionPlan.getExt(), resolveSetting(settings, "ext")));
        merged.setExtFile(firstNonBlank(extensionPlan.getExtFile(), resolveSetting(settings, "extFile", "ext-file")));
        merged.setSteps(extensionPlan.getSteps() == null ? List.of() : extensionPlan.getSteps());
        merged.setLoadedFrom(extensionPlan.getLoadedFrom());
        return merged;
    }

    private ExtensionPlanSpec loadDiscoveredExtensionPlan(RunPlan basePlan, String resolvedTemplate, Path path) {
        String normalizedActual = normalizePath(path.toAbsolutePath().normalize().toString());
        RunPlan extensionPlan = loadPlanFromSource(path.toString(), "extension plan " + path.getFileName());
        RunPlan merged = mergeExtensionPlan(basePlan, extensionPlan);

        String extFile = firstNonBlank(
                merged.getExtFile(),
                resolveSetting(merged.getSettings(), "extFile", "ext-file"),
                extractTokenValue(resolvedTemplate, normalizedActual, "{{extFile}}")
        );

        String ext = firstNonBlank(
                merged.getExt(),
                resolveSetting(merged.getSettings(), "ext"),
                extractTokenValue(resolvedTemplate, normalizedActual, "{{ext}}"),
                extFile
        );

        if (!StringUtils.hasText(ext) && !StringUtils.hasText(extFile)) {
            throw new IllegalStateException("Extension plan " + path + " must define ext/extFile in settings or match the template token");
        }

        String normalizedExt = StringUtils.hasText(ext) ? ext.trim() : extFile.trim();
        String normalizedExtFile = StringUtils.hasText(extFile) ? extFile.trim() : safeFileName(normalizedExt);
        merged.setExt(normalizedExt);
        merged.setExtFile(normalizedExtFile);
        return new ExtensionPlanSpec(normalizedExt, normalizedExtFile, merged);
    }

    private String renderExtensionSource(String template, String extensionName, String extFile) {
        String rendered = template == null ? "" : template.trim();
        rendered = rendered.replace("{{ext}}", extensionName == null ? "" : extensionName);
        rendered = rendered.replace("{{extFile}}", extFile == null ? "" : extFile);
        return rendered;
    }

    private String resolveRelativeSource(String baseSource, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return candidate;
        }
        String trimmed = candidate.trim();
        if (trimmed.startsWith("classpath:")) {
            return trimmed;
        }
        if (!StringUtils.hasText(baseSource)) {
            return trimmed;
        }

        if (baseSource.startsWith("classpath:")) {
            String basePath = baseSource.substring("classpath:".length());
            String baseDir = basePath.contains("/") ? basePath.substring(0, basePath.lastIndexOf('/') + 1) : "";
            return "classpath:" + normalizeClasspath(baseDir + trimmed);
        }

        if (looksLikeAbsolutePath(trimmed)) {
            return trimmed;
        }
        Path baseFile = Path.of(baseSource).toAbsolutePath().normalize();
        Path resolved = baseFile.getParent() == null
                ? Paths.get(trimmed).toAbsolutePath().normalize()
                : baseFile.getParent().resolve(trimmed).normalize();
        return resolved.toString();
    }

    private boolean looksLikeAbsolutePath(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        if (value.startsWith("/") || value.startsWith("\\\\")) {
            return true;
        }
        return value.length() > 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }

    private String normalizeClasspath(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String extractTokenValue(String template, String actual, String token) {
        String normalizedTemplate = normalizePath(template);
        String normalizedActual = normalizePath(actual);

        int markerIndex = normalizedTemplate.indexOf(token);
        if (markerIndex < 0) {
            return null;
        }

        String prefix = normalizedTemplate.substring(0, markerIndex);
        String suffix = normalizedTemplate.substring(markerIndex + token.length());
        if (!normalizedActual.startsWith(prefix) || !normalizedActual.endsWith(suffix)) {
            return null;
        }

        int endIndex = normalizedActual.length() - suffix.length();
        if (endIndex < prefix.length()) {
            return null;
        }
        return normalizedActual.substring(prefix.length(), endIndex);
    }

    private String resolveSetting(Map<String, String> settings, String... aliases) {
        if (settings == null || settings.isEmpty()) {
            return null;
        }
        for (String alias : aliases) {
            if (!StringUtils.hasText(alias)) {
                continue;
            }
            String value = settings.get(alias);
            if (StringUtils.hasText(value)) {
                return value;
            }
            String runnerValue = settings.get("runner." + alias);
            if (StringUtils.hasText(runnerValue)) {
                return runnerValue;
            }
        }
        return null;
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) {
            throw new IOException("Classpath resource not found: " + path);
        }
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private String firstNonBlank(String first, String second, String third) {
        String value = firstNonBlank(first, second);
        return value != null && !value.isBlank() ? value : third;
    }

    private String firstNonBlank(String first, String second, String third, String fourth) {
        String value = firstNonBlank(first, second, third);
        return value != null && !value.isBlank() ? value : fourth;
    }

    private String safeFileName(String value) {
        if (!StringUtils.hasText(value)) {
            return "ext";
        }
        String normalized = value.trim().replaceAll("[^0-9A-Za-zА-Яа-я._-]+", "_");
        if (normalized.length() > 60) {
            normalized = normalized.substring(0, 60);
        }
        return normalized.isBlank() ? "ext" : normalized;
    }
}
