package git.autoupdateservice.web;

import git.autoupdateservice.domain.TaskStatus;
import git.autoupdateservice.repo.UpdateTaskRepository;
import git.autoupdateservice.service.QueueService;
import git.autoupdateservice.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import java.time.LocalDate;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class QueueController {

    private final UpdateTaskRepository updateTaskRepository;
    private final QueueService queueService;
    private final SettingsService settingsService;

    @GetMapping("/queue")
    public String queue(@RequestParam(required = false) LocalDate from,
                        @RequestParam(required = false) LocalDate to,
                        @RequestParam(required = false) TaskStatus status,
                        @RequestParam(required = false, defaultValue = "1") int page,
                        Model model) {

        if (page < 1) page = 1;

        boolean hasFilters = (from != null || to != null || status != null);
        var s = settingsService.get();
        int pageSize = SettingsService.normalizePageSize(s.getQueuePageSize(), 50);

        var pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        var p = hasFilters
                ? updateTaskRepository.search(from, to, status, pageable)
                : updateTaskRepository.findAll(pageable);

        var tasks = p.getContent();

        model.addAttribute("tasks", tasks);
        model.addAttribute("page", page);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("totalPages", Math.max(1, p.getTotalPages()));
        model.addAttribute("totalElements", p.getTotalElements());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("status", status);

        // форматирование created_at в читаемый вид
        ZoneId zone;
        try { zone = ZoneId.of(s.getTimezone()); } catch (Exception e) { zone = ZoneId.systemDefault(); }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        Map<String, String> createdAtFmt = new HashMap<>();
        for (var t : tasks) {
            if (t.getId() != null && t.getCreatedAt() != null) {
                String ss = t.getCreatedAt().atZoneSameInstant(zone).format(fmt);
                createdAtFmt.put(t.getId().toString(), ss);
            }
        }
        model.addAttribute("createdAtFmt", createdAtFmt);

        // счётчики для UI (опционально, но удобно)
        model.addAttribute("countNew", updateTaskRepository.countByStatus(TaskStatus.NEW));
        model.addAttribute("countCanceled", updateTaskRepository.countByStatus(TaskStatus.CANCELED));
        model.addAttribute("countUpdated", updateTaskRepository.countByStatus(TaskStatus.UPDATED));

        return "queue";
    }

    @PostMapping("/queue/{id}/toggle")
    public String toggle(@PathVariable UUID id, HttpServletRequest request, Authentication auth) {
        queueService.toggleCancel(id, IpUtil.clientIp(request), auth != null ? auth.getName() : "anonymous");
        return "redirect:/queue";
    }
}