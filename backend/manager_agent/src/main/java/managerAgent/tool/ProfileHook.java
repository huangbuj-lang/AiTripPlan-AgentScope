package managerAgent.tool;

/** 画像增量维护钩子：数据源变动时异步触发该用户画像全量重算。 */
public final class ProfileHook {  // 类声明：画像增量维护钩子（工具类）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private ProfileHook() {}  // 私有构造：禁止 new
    /**
     * 作用：异步触发指定用户的画像全量重算（起独立线程跑 data-pipeline/profile.py），不阻塞调用方
     * 参数：userId 用户 ID，作为脚本 --user 参数
     * 返回值：无
     * 异常：无（脚本缺失或启动失败时静默返回，绝不影响主流程）
     */

    public static void trigger(long userId) {  // trigger 签名：异步触发画像重算
        try {  // try：任何失败都不影响主流程
            java.io.File script = new java.io.File(System.getProperty("user.dir"),  // 脚本路径 = 工作目录/data-pipeline/profile.py
                    "data-pipeline/profile.py");  // 续行
            if (!script.exists()) return;  // 脚本不存在就直接返回（没装数据管线）
            final ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath(),  // 用配置的 python（PYTHON_BIN，默认 python）起子进程，
                    "--user", String.valueOf(userId));  // 续：参数 --user <userId>
            pb.redirectErrorStream(true);  // 把 stderr 合并进 stdout，避免管道阻塞
            pb.redirectOutput(java.io.File.createTempFile("atp_profile_", ".log"));  // 输出重定向到临时日志文件（否则缓冲区写满会卡住子进程）
            new Thread(() -> {  // 起独立线程跑，不阻塞调用方
                try {  // try
                    Process pr = pb.start();  // 启动子进程
                    pr.waitFor();  // 等它跑完
                } catch (Exception ignored) { }  // 异常直接忽略
            }, "profile-rebuild-" + userId).start();  // 线程名带 userId，便于排查；启动线程
        } catch (Exception ignored) { }  // if 结束
    }  // 外层异常忽略（脚本缺失/启动失败都静默）
}  // 方法结束

