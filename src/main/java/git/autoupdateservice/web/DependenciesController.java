package git.autoupdateservice.web;

import git.autoupdateservice.domain.CodeSourceRoot;
import git.autoupdateservice.domain.DependencyCallerType;
import git.autoupdateservice.domain.SourceKind;
import git.autoupdateservice.repo.CodeSourceRootRepository;
import git.autoupdateservice.service.DependenciesPageService;
import git.autoupdateservice.service.DependencyTreeBuildService;
import git.autoupdateservice.service.DependencyTreeSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DependenciesController {

    private final DependencyTreeBuildService dependencyTreeBuildService;
    private final DependencyTreeSearchService dependencyTreeSearchService;
    private final CodeSourceRootRepository codeSourceRootRepository;
    private final DependenciesPageService dependenciesPageService;

    @GetMapping("/dependencies")
    public String page(
            @RequestParam(required = false, defaultValue = "module") String mode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType,
            Model model
    ) {
        dependenciesPageService.buildPageData(mode, q, objectType).applyTo(model);
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

    @GetMapping("/dependencies/tree/modules")
    @ResponseBody
    public List<DependencyTreeSearchService.ModuleNode> treeModules(
            @RequestParam UUID snapshotId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return dependencyTreeSearchService.findModules(snapshotId, q, objectType, limit, offset);
    }

    @GetMapping("/dependencies/tree/modules/count")
    @ResponseBody
    public Map<String, Long> treeModuleCount(
            @RequestParam UUID snapshotId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return Map.of("total", dependencyTreeSearchService.countModules(snapshotId, q, objectType));
    }

    @GetMapping("/dependencies/tree/methods")
    @ResponseBody
    public List<DependencyTreeSearchService.MethodNode> treeMethods(
            @RequestParam UUID snapshotId,
            @RequestParam String moduleName,
            @RequestParam SourceKind sourceKind,
            @RequestParam String sourceName,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return dependencyTreeSearchService.findMethods(snapshotId, moduleName, sourceKind, sourceName, objectType);
    }

    @GetMapping("/dependencies/tree/objects")
    @ResponseBody
    public List<DependencyTreeSearchService.ObjectNode> treeObjects(
            @RequestParam UUID snapshotId,
            @RequestParam String moduleName,
            @RequestParam String methodName,
            @RequestParam SourceKind sourceKind,
            @RequestParam String sourceName,
            @RequestParam(required = false) DependencyCallerType objectType
    ) {
        return dependencyTreeSearchService.findObjects(snapshotId, moduleName, methodName, sourceKind, sourceName, objectType);
    }
}
