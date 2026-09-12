package routeMakingAgent.mcp;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

//百度地图 MCP Server（当前被BaiduMapTool替代，保留作参考）


@Slf4j
public class BaiduMapMCP {

    private McpClientWrapper baiduMapMCP = null; // MCP客户端
    private boolean mcpInitialized = false; // MCP客户端初始化标志

//创建百度地图MCP客户端


    //@Tool(description = "百度地图MCP Server")
    public void getBaiduMapMCP() {

//log.info("==================");
//log.info("正在调用百度地图MCP....");
//log.info("==================");

        baiduMapMCP = McpClientBuilder.create("BaiduMap-mcp") // 创建MCP客户端
                //以streamable_http方式通信
                .streamableHttpTransport(System.getenv("BAIDU_MCP_URL"))
                .timeout(Duration.ofSeconds(120)) // 请求超时
                .buildAsync() // 异步请求
                .block();

    }

//初始化百度地图MCP客户端


    public McpClientWrapper initBaiduMapMCP() {

        Optional<McpClientWrapper> mcpClientWrapper = Optional.ofNullable(baiduMapMCP); // 判断百度MCP客户端是否为null
        if(mcpClientWrapper.isPresent()) {
            log.info("==================");
            log.info("百度MCP客户端已经创建");
            log.info("==================");

            if(!mcpInitialized) {
                synchronized (this) {
                    if (!mcpInitialized) {

                        baiduMapMCP.initialize().block(); // MCP客户端初始化

                        if(baiduMapMCP.isInitialized()) { // 获取MCP服务端工具列表

                            log.info("=============");
                            log.info("百度地图MCP 客户端初始化成功！");
                            log.info("=============");

                            baiduMapMCP.listTools().block().forEach(tool -> {
                                log.info("==================");
                                log.info("百度地图MCP工具列表：" + tool.name());
                                log.info("==================");
                            });

                            mcpInitialized=true;
                        }

                    }
                }
            }

        }

        return baiduMapMCP;

    }
}
