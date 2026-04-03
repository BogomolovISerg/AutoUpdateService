package git.autoupdateservice.service.steps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import git.autoupdateservice.config.RunnerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class StepPlanLoader {

    private static final String DEFAULT_CLASSPATH = "runner-steps.default.json";

    private final RunnerProperties runnerProperties;
    private final ObjectMapper objectMapper;

    public List<RunStepDef> loadSteps() {
        String src = runnerProperties.stepsFile();

        if (src != null && !src.isBlank()) {
            src = src.trim();

            try {
                String json;

                if (src.startsWith("classpath:")) {
                    String cp = src.substring("classpath:".length());
                    json = readClasspath(cp.startsWith("/") ? cp.substring(1) : cp);
                    log.info("Loaded steps plan from classpath resource: {}", src);
                } else {
                    Path p = Path.of(src).toAbsolutePath().normalize();

                    if (!Files.exists(p)) {
                        throw new IllegalStateException("Steps file not found: " + p);
                    }

                    json = Files.readString(p, StandardCharsets.UTF_8);
                    log.info("Loaded steps plan from external file: {}", p);
                }

                return parse(json, src);

            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot load steps plan from configured source: " + src + ". " +
                                "If this is an external file, ensure it exists and is saved in UTF-8. " +
                                "Error: " + e.getMessage(), e
                );
            }
        }

        try {
            String json = readClasspath(DEFAULT_CLASSPATH);
            log.warn("runner.steps-file is not set. Fallback to classpath:{}", DEFAULT_CLASSPATH);
            return parse(json, "classpath:" + DEFAULT_CLASSPATH);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot load default steps plan from classpath:" + DEFAULT_CLASSPATH + ". Error: " + e.getMessage(), e
            );
        }
    }

    private List<RunStepDef> parse(String json, String source) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<RunStepDef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid steps plan JSON. Source=" + source + ". Error=" + e.getMessage(), e);
        }
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
}