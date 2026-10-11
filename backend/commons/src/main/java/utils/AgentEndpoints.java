package utils;

/**
 * ★2026-09-11 端点集中配置（评审点：13+ 处 {@code localhost:8xxx} 散落在 commons / manager 各处，
 * 换部署环境要全局搜替换，漏一处就是运行期连错服务）。
 *
 * <p>本类把「谁在哪」集中到一处，全部可由环境变量（或 Nacos 配置）覆盖，默认值保证本地开发开箱即用。
 * 新增服务/改地址时只改这里。
 *
 * <p>对应环境变量：
 * <ul>
 *   <li>{@code NACOS_SERVER_ADDR}  Nacos 地址（host:port），默认 localhost:8848</li>
 *   <li>{@code NACOS_CONSOLE_URL}  Nacos 3.x 控制台，默认 http://localhost:8080</li>
 *   <li>{@code AGENT_ROUTE_URL}    路线Agent A2A 服务，默认 http://localhost:8082</li>
 *   <li>{@code AGENT_TRIP_URL}     行程Agent A2A 服务，默认 http://localhost:8085</li>
 *   <li>{@code MANAGER_URL}        主管服务（含前端），默认 http://localhost:8091</li>
 * </ul>
 */
public final class AgentEndpoints {

    private AgentEndpoints() {}

    /** Nacos 注册/配置中心地址（host:port，不带协议） */
    public static String nacosAddr() {
        return Config.get("NACOS_SERVER_ADDR", "localhost:8848");
    }

    /** Nacos 3.x 控制台地址 */
    public static String nacosConsole() {
        return Config.get("NACOS_CONSOLE_URL", "http://localhost:8080");
    }

    /** 路线Agent（A2A 服务）基础地址 */
    public static String routeAgent() {
        return Config.get("AGENT_ROUTE_URL", "http://localhost:8082");
    }

    /** 行程Agent（A2A 服务）基础地址 */
    public static String tripAgent() {
        return Config.get("AGENT_TRIP_URL", "http://localhost:8085");
    }

    /** 主管服务（含前端静态页）基础地址 */
    public static String manager() {
        return Config.get("MANAGER_URL", "http://localhost:8091");
    }

    /** 按 agent 名取基础地址（RouteMakingAgent / TripPlannerAgent），未知名字回退主管自身 */
    public static String forAgent(String agentName) {
        if ("RouteMakingAgent".equals(agentName)) return routeAgent();
        if ("TripPlannerAgent".equals(agentName)) return tripAgent();
        return manager();
    }

    /** 按中文/英文标签容错取子Agent地址（历史调用点用的是「路线」「行程」这种中文标签） */
    public static String forAgentLabel(String label) {
        if (label == null) return manager();
        if (label.contains("路线") || label.contains("Route")) return routeAgent();
        if (label.contains("行程") || label.contains("Trip")) return tripAgent();
        return manager();
    }

    /** 从基础地址取端口（无端口返回 -1），用于原来只传端口的调用点 */
    public static int portOf(String baseUrl) {
        try {
            java.net.URI u = java.net.URI.create(baseUrl);
            return u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 路线Agent 端口（便捷方法，供只接受 int port 的历史调用点用） */
    public static int routePort() { return portOf(routeAgent()); }

    /** 行程Agent 端口（便捷方法） */
    public static int tripPort() { return portOf(tripAgent()); }
}
