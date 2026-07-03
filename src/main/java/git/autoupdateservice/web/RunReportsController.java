package git.autoupdateservice.web;

import git.autoupdateservice.service.AllureReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class RunReportsController {

    private final AllureReportService allureReportService;

    @GetMapping({"/runs/{runId}/allure", "/runs/{runId}/allure/"})
    public String allureReport(@PathVariable UUID runId, Model model) {
        if (!allureReportService.hasReport(runId)) {
            populateMissing(model, runId);
            return "allure-missing";
        }
        return "redirect:/runs/" + runId + "/allure/index.html";
    }

    @GetMapping("/runs/{runId}/allure/index.html")
    public Object allureIndex(@PathVariable UUID runId, Model model) throws Exception {
        if (!allureReportService.hasReport(runId)) {
            populateMissing(model, runId);
            return "allure-missing";
        }
        return serve(allureReportService.resolveIndexFile(runId));
    }

    @GetMapping("/runs/{runId}/allure/{*relativePath}")
    public ResponseEntity<Resource> allureAsset(
            @PathVariable UUID runId,
            @PathVariable String relativePath
    ) throws Exception {
        Path file;
        try {
            file = allureReportService.resolveReportFile(runId, relativePath);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return serve(file);
    }

    private void populateMissing(Model model, UUID runId) {
        model.addAttribute("runId", runId);
        model.addAttribute("message", "Отчет Allure для этого прогона не найден. Возможно, он был удален очисткой runner-logs.");
    }

    private ResponseEntity<Resource> serve(Path file) throws Exception {
        Resource resource = new UrlResource(file.toUri());
        MediaType mediaType = MediaTypeFactory.getMediaType(file.getFileName().toString())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .contentLength(Files.size(file))
                .contentType(mediaType)
                .body(resource);
    }
}
