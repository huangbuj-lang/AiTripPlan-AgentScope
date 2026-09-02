package managerAgent.tool;

/**
 * ★画像增量维护钩子：数据源变动时异步触发该用户画像全量重算。
 * 数据源：trip_plans(保存/删除行程) / posts(发帖删帖) / user_preferences(个人中心设置)
 * 实现：异步调 data-pipeline/profile.py --user <id>（剩余数据全量聚合，共同偏好不误删），失败静默。
 */
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
