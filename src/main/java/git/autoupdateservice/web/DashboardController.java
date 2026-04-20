package git.autoupdateservice.web;

import git.autoupdateservice.repo.ExecutionRunRepository;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final SettingsService settingsService;
    private final UpdateTaskRepository updateTaskRepository;
    private final ExecutionRunRepository executionRunRepository;

    @GetMapping("/")
    public String dashboard(Model model, Authentication auth) {
        var settings = settingsService.get();
        var recentRuns = executionRunRepository.findTop20ByOrderByStartedAtDesc();

        ZoneId zone;
        try {
            zone = ZoneId.of(settings.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }

        Map<UUID, String> plannedForFmt = new HashMap<>();
        Map<UUID, String> dependencySnapshotFmt = new HashMap<>();
        for (var r : recentRuns) {
            if (r.getId() != null && r.getPlannedFor() != null) {
                plannedForFmt.put(
                        r.getId(),
                        r.getPlannedFor().atZoneSameInstant(zone).format(TS_FMT)
                );
            }
            if (r.getId() != null && r.getDependencySnapshot() != null && r.getDependencySnapshot().getId() != null) {
                dependencySnapshotFmt.put(r.getId(), String.valueOf(r.getDependencySnapshot().getId()));
            }
        }

        model.addAttribute("settings", settings);
        model.addAttribute("pendingNewCount", updateTaskRepository.countByStatus(git.autoupdateservice.domain.TaskStatus.NEW));
        model.addAttribute("recentNewTasks", updateTaskRepository.findTop200ByStatusOrderByCreatedAtDesc(git.autoupdateservice.domain.TaskStatus.NEW));
        model.addAttribute("recentRuns", recentRuns);
        model.addAttribute("plannedForFmt", plannedForFmt);
        model.addAttribute("dependencySnapshotFmt", dependencySnapshotFmt);
        model.addAttribute("actor", auth != null ? auth.getName() : "anonymous");
        return "dashboard";
    }
}
