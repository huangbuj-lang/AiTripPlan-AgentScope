package routeMakingAgent.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

//百度地图 MCP Server（当前被BaiduMapTool替代，保留作参考）


@Slf4j  // @Slf4j：注入日志对象
public class BaiduMapMCP {  // 类声明：百度地图 MCP 客户端封装（当前被 BaiduMapTool 替代，保留作参考）

    private McpClientWrapper baiduMapMCP = null; // MCP客户端  // baiduMapMCP：MCP 客户端实例（初始 null）
    private boolean mcpInitialized = false; // MCP客户端初始化标志  // mcpInitialized：是否已 initialize 过（防重复初始化）

//创建百度地图MCP客户端


    //@Tool(description = "百度地图MCP Server")
    /**
     * 作用：创建百度地图 MCP 客户端（streamable HTTP，120s 超时），结果存入字段供后续复用
     * 参数：无
     * 返回值：无
     * 异常：RuntimeException 连接或构建失败时由 block() 抛出，方法未捕获
     */
    public void getBaiduMapMCP() {  // getBaiduMapMCP：创建 MCP 客户端（无入参）

//log.info("==================");
//log.info("正在调用百度地图MCP....");
//log.info("==================");

        baiduMapMCP = McpClientBuilder.create("BaiduMap-mcp") // 创建MCP客户端  // McpClientBuilder.create：客户端名 BaiduMap-mcp
                //以streamable_http方式通信
                .streamableHttpTransport(System.getenv("BAIDU_MCP_URL"))  // 以 streamable HTTP 方式连服务端（地址取环境变量 BAIDU_MCP_URL）
                .timeout(Duration.ofSeconds(120)) // 请求超时  // 单次请求超时 120 秒
                .buildAsync() // 异步请求  // buildAsync：异步构建
                .block();  // block()：阻塞等服务端握手完成（失败会抛 RuntimeException）

    }  // 方法结束

//初始化百度地图MCP客户端
    /**
     * 作用：初始化百度地图 MCP 客户端（双重检查锁只初始化一次）并打印服务端工具列表
     * 参数：无
     * 返回值：MCP 客户端包装器；尚未创建时返回 null
     * 异常：无（未创建时不初始化直接返回 null）
     */


    public McpClientWrapper initBaiduMapMCP() {  // initBaiduMapMCP：初始化客户端并打印工具列表

        Optional<McpClientWrapper> mcpClientWrapper = Optional.ofNullable(baiduMapMCP); // 判断百度MCP客户端是否为null  // Optional 包一层，判断客户端是否已创建
        if(mcpClientWrapper.isPresent()) {  // 已创建
            log.info("==================");  // 分隔线日志
            log.info("百度MCP客户端已经创建");  // 日志：客户端已创建
            log.info("==================");  // 分隔线日志

            if(!mcpInitialized) {  // 还没初始化过
                synchronized (this) {  // 对当前实例加锁（双重检查）
                    if (!mcpInitialized) {  // 锁内再判一次，防止并发重复初始化

                        baiduMapMCP.initialize().block(); // MCP客户端初始化  // initialize().block()：阻塞完成 MCP 握手

                        if(baiduMapMCP.isInitialized()) { // 获取MCP服务端工具列表  // 初始化成功

                            log.info("=============");  // 分隔线
                            log.info("百度地图MCP 客户端初始化成功！");  // 日志：初始化成功
                            log.info("=============");  // 分隔线

                            baiduMapMCP.listTools().block().forEach(tool -> {  // listTools().block() 取服务端工具列表并逐个打印
                                log.info("==================");  // 分隔线
                                log.info("百度地图MCP工具列表：" + tool.name());  // 日志：工具名
                                log.info("==================");  // 分隔线
                            });  // forEach 结束

                            mcpInitialized=true;  // 置初始化标志
                        }  // if 结束

                    }  // if 结束
                }  // synchronized 结束
            }  // if 结束

        }  // if 结束

        return baiduMapMCP;  // 返回客户端（可能为 null）

    }  // 方法结束
}  // 类结束

