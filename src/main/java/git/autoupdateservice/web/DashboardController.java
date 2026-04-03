package git.autoupdateservice.web;

import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SettingsService settingsService;
    private final UpdateTaskRepository updateTaskRepository;
    private final ExecutionRunRepository executionRunRepository;

    @GetMapping("/")
    public String dashboard(Model model, Authentication auth) {
        model.addAttribute("settings", settingsService.get());
        model.addAttribute("pendingNewCount", updateTaskRepository.countByStatus(git.autoupdateservice.domain.TaskStatus.NEW));
        model.addAttribute("recentNewTasks", updateTaskRepository.findTop200ByStatusOrderByCreatedAtDesc(git.autoupdateservice.domain.TaskStatus.NEW));
        model.addAttribute("recentRuns", executionRunRepository.findAll().stream()
                .sorted((a,b) -> b.getPlannedFor().compareTo(a.getPlannedFor()))
                .limit(20).toList());
        model.addAttribute("actor", auth != null ? auth.getName() : "anonymous");
        return "dashboard";
    }
}
