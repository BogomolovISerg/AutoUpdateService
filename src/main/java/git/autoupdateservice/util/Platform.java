package git.autoupdateservice.util;

public final class Platform {
    private Platform() {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name");
        if (os == null) return false;
        return os.toLowerCase().contains("win");
    }
}
