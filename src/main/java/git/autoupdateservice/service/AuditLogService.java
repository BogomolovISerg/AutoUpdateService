package git.autoupdateservice.service;

import git.autoupdateservice.domain.*;
import git.autoupdateservice.repo.LogEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import git.autoupdateservice.util.PasswordMasker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final LogEventRepository logEventRepository;

    private static final ObjectMapper OM = new ObjectMapper();

    public void info(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        save(type, LogLevel.INFO, message, dataJson, clientIp, actor, runId);
    }
    public void warn(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        save(type, LogLevel.WARN, message, dataJson, clientIp, actor, runId);
    }
    public void error(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        save(type, LogLevel.ERROR, message, dataJson, clientIp, actor, runId);
    }

   public LogEvent infoReturn(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        return saveReturn(type, LogLevel.INFO, message, dataJson, clientIp, actor, runId);
    }

    public LogEvent warnReturn(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        return saveReturn(type, LogLevel.WARN, message, dataJson, clientIp, actor, runId);
    }

    public LogEvent errorReturn(LogType type, String message, String dataJson, String clientIp, String actor, UUID runId) {
        return saveReturn(type, LogLevel.ERROR, message, dataJson, clientIp, actor, runId);
    }

    private void save(LogType type, LogLevel level, String message, String dataJson, String clientIp, String actor, UUID runId) {
        LogEvent e = new LogEvent();
        e.setTs(OffsetDateTime.now());
        e.setType(type);
        e.setLevel(level);
        e.setMessage(PasswordMasker.maskText(message));
        e.setData(normalizeJson(PasswordMasker.maskText(dataJson)));
        e.setClientIp(clientIp);
        e.setActor(actor);
        e.setRunId(runId);
        logEventRepository.save(e);
    }

    private LogEvent saveReturn(LogType type, LogLevel level, String message, String dataJson, String clientIp, String actor, UUID runId) {
        LogEvent e = new LogEvent();
        e.setTs(OffsetDateTime.now());
        e.setType(type);
        e.setLevel(level);
        e.setMessage(PasswordMasker.maskText(message));
        e.setData(normalizeJson(PasswordMasker.maskText(dataJson)));
        e.setClientIp(clientIp);
        e.setActor(actor);
        e.setRunId(runId);
        return logEventRepository.save(e);
    }

    private static String normalizeJson(String dataJson) {
        if (dataJson == null) return "{}";
        String s = dataJson.trim();
        if (s.isEmpty()) return "{}";

        try {
            OM.readTree(s);
            return s;
        } catch (Exception ignore) {
            try {
                String quoted = OM.writeValueAsString(s);
                return "{\"text\":" + quoted + "}";
            } catch (Exception e) {
                String safe = s.replace("\\", "\\\\").replace("\"", "\\\"");
                return "{\"text\":\"" + safe + "\"}";
            }
        }
    }
}

