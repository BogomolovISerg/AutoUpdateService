package git.autoupdateservice.web;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.service.DependencyGraphStateService;
import git.autoupdateservice.service.DependencyTreeBuildService;
import git.autoupdateservice.service.DependencyTreeSearchService;
import git.autoupdateservice.repo.DependencyScanLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;

@Controller
@RequiredArgsConstructor
public class DependenciesController {

    private final DependencyTreeBuildService dependencyTreeBuildService;
    private final DependencyTreeSearchService dependencyTreeSearchService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependencyGraphStateService dependencyGraphStateService;
    private final DependencyScanLogRepository dependencyScanLogRepository;

    @GetMapping("/dependencies")
    public String page(
            @RequestParam(required = false, defaultValue = "module") String mode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType,
            Model model
    ) {
        var snapshot = dependencyTreeSearchService.latestSnapshot().orElse(null);
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("scanLogs",
                snapshot == null
                        ? java.util.List.of()
                        : dependencyScanLogRepository.findTop200BySnapshotOrderByCreatedAtDesc(snapshot));
        model.addAttribute("mode", mode);
        model.addAttribute("q", q);
        model.addAttribute("objectType", objectType);
        model.addAttribute("rows", dependencyTreeSearchService.findRows(mode, q, objectType));
        model.addAttribute("baseSource", codeSourceRootRepository.findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind.BASE).orElse(null));
        model.addAttribute("graphState", dependencyGraphStateService.getState());
        model.addAttribute("dirtyItems", dependencyGraphStateService.latestDirtyItems());
        return "dependencies";
    }

    @PostMapping("/dependencies/rebuild")
    public String rebuild(RedirectAttributes ra) {
        try {
            var snapshot = dependencyTreeBuildService.fullRebuild();
            ra.addFlashAttribute("message", "Сканирование завершено. Статус: " + snapshot.getStatus());
        } catch (Exception e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/dependencies";
    }

    @PostMapping("/dependencies/source/save")
    public String saveBaseSource(
            @RequestParam String sourceName,
            @RequestParam String rootPath,
            @RequestParam(defaultValue = "false") boolean enabled,
            RedirectAttributes ra
    ) {
        CodeSourceRoot source = codeSourceRootRepository
                .findFirstBySourceKindOrderByUpdatedAtDesc(SourceKind.BASE)
                .orElseGet(CodeSourceRoot::new);

        source.setSourceKind(SourceKind.BASE);
        source.setSourceName(sourceName == null || sourceName.isBlank() ? "Основная конфигурация" : sourceName.trim());
        source.setRootPath(rootPath == null ? "" : rootPath.trim());
        source.setEnabled(enabled);
        source.setPriority(0);
        source.setUpdatedAt(OffsetDateTime.now());
        codeSourceRootRepository.save(source);

        ra.addFlashAttribute("message", "Источник основной конфигурации сохранён");
        return "redirect:/dependencies";
    }
}
