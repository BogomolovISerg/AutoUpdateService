package git.autoupdateservice.service;

import org.springframework.stereotype.Component;

@Component
public class DependencyGraphRebuildScheduler {
    // Automatic background rebuild is intentionally disabled.
    // The graph is marked stale by webhook processing and rebuilt:
    // 1. manually from the UI
    // 2. before TEST runs
}
