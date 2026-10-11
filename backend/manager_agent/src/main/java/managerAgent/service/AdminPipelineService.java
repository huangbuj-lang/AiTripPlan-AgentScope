package managerAgent.service;

import org.springframework.web.bind.annotation.*;
import utils.Config;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** AdminPipeline 的业务实现（自原控制器等价平移，逻辑未改）。 */
@Service
public class AdminPipelineService {


    private final java.util.Map<String, String> PIPELINE_JOBS = new java.util.concurrent.ConcurrentHashMap<>();


    private final java.util.concurrent.ExecutorService PIPELINE_POOL = java.util.concurrent.Executors.newSingleThreadExecutor();


    private volatile Process PIPELINE_PROC; // 当前更新子进程

    public Map<String, Object> runPricing() {
        Map<String, Object> r = new HashMap<>();
        try {
            java.io.File script = new java.io.File(managerAgent.tool.ProjectPaths.root(), "data-pipeline/pricing.py");
            if (script == null) { r.put("success", false); r.put("message", "找不到 pricing.py"); return r; }
            ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath());
            pb.directory(new java.io.File(script.getCanonicalPath()).getParentFile().getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); r.put("success", false); r.put("message", "定价更新超时"); return r; }
            r.put("success", p.exitValue() == 0);
            r.put("message", p.exitValue() == 0 ? "定价已更新" : "定价脚本退出码 " + p.exitValue());
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "执行失败: " + e.getMessage());
        }
        return r;
    }

    public Map<String, Object> runForecast() {
        Map<String, Object> r = new HashMap<>();
        try {
            java.io.File script = new java.io.File(managerAgent.tool.ProjectPaths.root(), "data-pipeline/forecast.py");
            if (script == null) { r.put("success", false); r.put("message", "找不到 forecast.py"); return r; }
            ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), script.getCanonicalPath());
            pb.directory(new java.io.File(script.getCanonicalPath()).getParentFile().getParentFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); r.put("success", false); r.put("message", "客流预测超时"); return r; }
            r.put("success", p.exitValue() == 0);
            r.put("message", p.exitValue() == 0 ? "客流预测已重算（基于全部已保存行程的本地统计）" : "预测脚本退出码 " + p.exitValue());
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", "执行失败: " + e.getMessage());
        }
        return r;
    }

    public Map<String, Object> runPipeline() {
        Map<String, Object> r = new HashMap<>();
        for (String s : PIPELINE_JOBS.values()) {
            if ("running".equals(s)) {
                r.put("success", false);
                r.put("message", "已有更新任务在执行中，请稍候");
                return r;
            }
        }
        String jobId = "pipe_" + System.currentTimeMillis();
        PIPELINE_JOBS.put(jobId, "running");
        java.io.File script = new java.io.File(managerAgent.tool.ProjectPaths.root(), "data-pipeline/update_all.py");
        final String scriptPath;
        try {
            scriptPath = script.getCanonicalPath();
        } catch (Exception e) {
            PIPELINE_JOBS.remove(jobId);
            r.put("success", false); r.put("message", "脚本路径错误");
            return r;
        }
        PIPELINE_POOL.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(utils.Config.get("PYTHON_BIN", "python"), scriptPath);
                pb.directory(new java.io.File(scriptPath).getParentFile().getParentFile());
                pb.redirectErrorStream(true);
                pb.redirectOutput(new java.io.File(System.getProperty("java.io.tmpdir"), "atp_pipeline_" + jobId + ".log"));
                Process p = pb.start();
                PIPELINE_PROC = p;
                p.waitFor();
                PIPELINE_JOBS.put(jobId, p.exitValue() == 0 ? "done" : "stopped_or_failed(exit=" + p.exitValue() + ")");
            } catch (Exception e) {
                PIPELINE_JOBS.put(jobId, "error: " + e.getMessage());
            }
        });
        r.put("success", true);
        r.put("jobId", jobId);
        r.put("message", "数据更新已在后台启动（采集→预处理→高德v5→等级→LLM，约30分钟）");
        return r;
    }

    public Map<String, Object> pipelineStatus() {
        Map<String, Object> r = new HashMap<>();
        r.put("jobs", PIPELINE_JOBS);
        r.put("running", PIPELINE_JOBS.values().stream().anyMatch("running"::equals));
        return r;
    }

    public Map<String, Object> stopPipeline() {
        Map<String, Object> r = new HashMap<>();
        Process p = PIPELINE_PROC;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            r.put("success", true);
            r.put("message", "已停止更新任务");
            return r;
        }
        boolean hasRunning = PIPELINE_JOBS.values().stream().anyMatch("running"::equals);
        r.put("success", hasRunning);
        r.put("message", hasRunning ? "已发送停止请求" : "当前没有正在执行的更新任务");
        return r;
    }
}
