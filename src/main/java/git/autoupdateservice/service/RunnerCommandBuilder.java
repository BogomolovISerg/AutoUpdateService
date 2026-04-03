package git.autoupdateservice.service;

import git.autoupdateservice.config.RunnerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RunnerCommandBuilder {

    private final RunnerProperties p;

    public List<String> scheduledJobsLock(Path debugLog) {
        return List.of(
                p.binary(), "scheduledjobs", "lock",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--ibconnection", p.ibConnection(),
                "--nocacheuse",
                "--debuglogfile", debugLog.toString(),
                "--nocacheuse"
        );
    }

    public List<String> scheduledJobsUnlock(Path debugLog) {
        return List.of(
                p.binary(), "scheduledjobs", "unlock",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--ibconnection", p.ibConnection(),
                "--nocacheuse",
                "--debuglogfile", debugLog.toString()
        );
    }

    public List<String> sessionLock(String message, String uccode, Path debugLog) {
        return List.of(
                p.binary(), "session", "lock",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--lockmessage", message,
                "--lockuccode", uccode,
                "--debuglogfile", debugLog.toString(),
                "--nocacheuse"
        );
    }

    public List<String> sessionUnlock(String message, String uccode, Path debugLog) {
        return List.of(
                p.binary(), "session", "unlock",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--lockmessage", message,
                "--lockuccode", uccode,
                "--debuglogfile", debugLog.toString()
        );
    }

    public List<String> sessionKill(Path debugLog) {
        return List.of(
                p.binary(), "session", "kill",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--debuglogfile", debugLog.toString(),
                "--nocacheuse"
        );
    }

    public List<String> sessionClosed(Path debugLog) {
        return List.of(
                p.binary(), "session", "closed",
                "--ras", p.rasAddress(),
                "--rac", p.racPath(),
                "--db", p.baseName(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--debuglogfile", debugLog.toString()
        );
    }

    public List<String> loadRepoMain(String repoPath, String uccode, Path debugLog) {
        return List.of(
                p.binary(), "loadrepo", repoPath,
                "--ibconnection", p.ibConnection(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--storage-user", p.repoUser(),
                "--storage-pwd", p.repoPassword(),
                "--uccode", uccode,
                "--debuglogfile", debugLog.toString()
        );
    }

    public List<String> loadRepoExtension(String repoPath, String ext, String uccode, Path debugLog) {
        List<String> cmd = new ArrayList<>(loadRepoMain(repoPath, uccode, debugLog));
        cmd.add("-extension");
        cmd.add(ext);
        return cmd;
    }

    public List<String> updateDb(String uccode, Path debugLog) {
        return List.of(
                p.binary(), "updatedb",
                "--ibcmd",
                "--ibconnection", p.ibConnection(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--uccode", uccode,
                "--debuglogfile", debugLog.toString(),
                "--nocacheuse"
        );
    }

    public List<String> updateExt(String repoPath, String uccode, Path debugLog) {
        return List.of(
                p.binary(), "updateext", repoPath,
                "--ibconnection", p.ibConnection(),
                "--db-user", p.dbUser(),
                "--db-pwd", p.dbPassword(),
                "--storage-user", p.repoUser(),
                "--storage-pwd", p.repoPassword(),
                "--uccode", uccode,
                "--debuglogfile", debugLog.toString()
        );
    }
}

