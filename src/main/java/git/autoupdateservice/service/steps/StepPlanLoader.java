package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.config.RunnerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Загружает план шагов из JSON.
 *
 * Приоритет:
 *  1) runner.steps-file (если это classpath:... или существующий файл)
 *  2) classpath:runner-steps.default.json (fallback)
 */
@Component
@RequiredArgsConstructor
public class StepPlanLoader {

    private static final String DEFAULT_CLASSPATH = "runner-steps.default.json";

    private final RunnerProperties runnerProperties;
    private final ObjectMapper objectMapper;

    public List<RunStepDef> loadSteps() {
        String src = runnerProperties.stepsFile();
        String json = null;

        if (src != null && !src.isBlank()) {
            src = src.trim();
            try {
                if (src.startsWith("classpath:")) {
                    String cp = src.substring("classpath:".length());
                    json = readClasspath(cp.startsWith("/") ? cp.substring(1) : cp);
                } else {
                    Path p = Path.of(src);
                    if (Files.exists(p)) {
                        json = Files.readString(p, StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception ignored) {
                // fallback below
            }
        }

        if (json == null) {
            try {
                json = readClasspath(DEFAULT_CLASSPATH);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load steps plan. Tried file=" + src
                        + " and classpath:" + DEFAULT_CLASSPATH + ". Error=" + e.getMessage(), e);
            }
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<RunStepDef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid steps plan JSON. Source=" + (src == null ? "(default)" : src)
                    + ". Error=" + e.getMessage(), e);
        }
    }

    private static String readClasspath(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        if (!r.exists()) throw new IOException("Classpath resource not found: " + path);
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
