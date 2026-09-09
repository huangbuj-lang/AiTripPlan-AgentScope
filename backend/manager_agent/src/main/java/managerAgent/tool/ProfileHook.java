package managerAgent.tool;

/** 画像增量维护钩子：数据源变动时异步触发该用户画像全量重算。 */
public final class ProfileHook {

    private ProfileHook() {}

    public static void trigger(long userId) {
        try {
            java.io.File script = new java.io.File(System.getProperty("user.dir"),
                    "data-pipeline/profile.py");
            if (!script.exists()) return;
            final ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath(),
                    "--user", String.valueOf(userId));
            pb.redirectErrorStream(true);
            pb.redirectOutput(java.io.File.createTempFile("atp_profile_", ".log"));
            new Thread(() -> {
                try {
                    Process pr = pb.start();
                    pr.waitFor();
                } catch (Exception ignored) { }
            }, "profile-rebuild-" + userId).start();
        } catch (Exception ignored) { }
    }
}
