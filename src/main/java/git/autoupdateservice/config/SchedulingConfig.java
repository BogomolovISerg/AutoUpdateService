package git.autoupdateservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@Configuration
public class SchedulingConfig {

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${app.scheduler.pool-size:4}") int poolSize
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, poolSize));
        scheduler.setThreadNamePrefix("auto-update-scheduler-");
        scheduler.setErrorHandler(error -> log.error("Unhandled scheduled task error", error));
        scheduler.initialize();
        return scheduler;
    }
}
