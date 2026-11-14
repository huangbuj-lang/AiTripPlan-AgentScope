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
public final class AgentEndpoints {  // 类声明：端点集中配置（谁在哪都从这里取）
    /**
     * 作用：私有构造，禁止实例化本工具类
     * 参数：无
     * 返回值：无
     * 异常：无
     */

    private AgentEndpoints() {}  // 私有构造：禁止 new

    /**
     * 作用：读取 Nacos 服务地址
     * 参数：无
     * 返回值：Nacos 地址
     * 异常：无
     */
    public static String nacosAddr() {  // nacosAddr：Nacos 服务地址
        return Config.get("NACOS_SERVER_ADDR", "localhost:8848");  // 读 NACOS_SERVER_ADDR（默认 localhost:8848）
    }  // 方法结束

    /**
     * 作用：读取 Nacos 控制台地址
     * 参数：无
     * 返回值：控制台 URL
     * 异常：无
     */
    public static String nacosConsole() {  // nacosConsole：Nacos 控制台地址
        return Config.get("NACOS_CONSOLE_URL", "http://localhost:8080");  // 读 NACOS_CONSOLE_URL（默认 8080 控制台）
    }  // 方法结束

    /**
     * 作用：读取路线子 Agent 的访问地址
     * 参数：无
     * 返回值：基础 URL
     * 异常：无
     */
    public static String routeAgent() {  // routeAgent：路线子 Agent 地址
        return Config.get("AGENT_ROUTE_URL", "http://localhost:8082");  // 读 AGENT_ROUTE_URL（默认 8082）
    }  // 方法结束

    /**
     * 作用：读取行程子 Agent 的访问地址
     * 参数：无
     * 返回值：基础 URL
     * 异常：无
     */
    public static String tripAgent() {  // tripAgent：行程子 Agent 地址
        return Config.get("AGENT_TRIP_URL", "http://localhost:8085");  // 读 AGENT_TRIP_URL（默认 8085）
    }  // 方法结束

    /**
     * 作用：读取主管 Agent 的访问地址
     * 参数：无
     * 返回值：基础 URL
     * 异常：无
     */
    public static String manager() {  // manager：主管服务地址
        return Config.get("MANAGER_URL", "http://localhost:8091");  // 读 MANAGER_URL（默认 8091）
    }  // 方法结束

    /**
     * 作用：按 Agent 名称取它的访问地址
     * 参数：agentName Agent 注册名（RouteMakingAgent/TripPlannerAgent）
     * 返回值：基础 URL；未知名称回落到主管地址
     * 异常：无
     */
    public static String forAgent(String agentName) {  // forAgent：按注册名取地址
        if ("RouteMakingAgent".equals(agentName)) return routeAgent();  // 路线 Agent → 8082
        if ("TripPlannerAgent".equals(agentName)) return tripAgent();  // 行程 Agent → 8085
        return manager();  // 未知 → 主管地址
    }  // 方法结束

    /**
     * 作用：按中文标签取 Agent 的访问地址
     * 参数：label 中文标签（路线Agent/行程Agent）
     * 返回值：基础 URL；未知标签回落到主管地址
     * 异常：无
     */
    public static String forAgentLabel(String label) {  // forAgentLabel：按中文标签取地址
        if (label == null) return manager();  // 标签为空 → 主管地址
        if (label.contains("路线") || label.contains("Route")) return routeAgent();  // 含“路线/Route” → 路线 Agent
        if (label.contains("行程") || label.contains("Trip")) return tripAgent();  // 含“行程/Trip” → 行程 Agent
        return manager();  // 其他 → 主管地址
    }  // 方法结束

    /**
     * 作用：从基础 URL 中解析端口
     * 参数：baseUrl 形如 http://localhost:8085 的地址
     * 返回值：端口号；解析失败返回 -1
     * 异常：无
     */
    public static int portOf(String baseUrl) {  // portOf：从 URL 解析端口
        try {  // try
            java.net.URI u = java.net.URI.create(baseUrl);  // 解析成 URI
            return u.getPort() > 0 ? u.getPort() : ("https".equals(u.getScheme()) ? 443 : 80);  // 有端口用端口，否则按协议给 443/80
        } catch (Exception e) {  // 解析失败
            return -1;  // 返回 -1
        }  // catch 结束
    }  // 方法结束

    /**
     * 作用：读取路线子 Agent 的端口
     * 参数：无
     * 返回值：端口号；解析失败返回 -1
     * 异常：无
     */
    public static int routePort() { return portOf(routeAgent()); }  // routePort：路线 Agent 端口

    /**
     * 作用：读取行程子 Agent 的端口
     * 参数：无
     * 返回值：端口号；解析失败返回 -1
     * 异常：无
     */
    public static int tripPort() { return portOf(tripAgent()); }  // tripPort：行程 Agent 端口
}  // 类结束
