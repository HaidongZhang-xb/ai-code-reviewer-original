# AI Code Reviewer · MCP Server 接入指南

本服务同时是一个 MCP (Model Context Protocol) Server,可被 Claude Code / Cursor / Cline 等支持 MCP 的 IDE 工具直接调用。

## 启动

确保主应用已启动(默认 8080):
mvn spring-boot:run

MCP 协议端点:
- SSE 连接: `http://localhost:8080/sse`
- 消息端点: `http://localhost:8080/mcp/message`

## 暴露的 Tools

| 工具名 | 作用 |
|---|---|
| `review_code` | 评审一段 Java 源码,返回结构化问题清单 |
| `generate_tests` | 为给定方法生成 JUnit 5 + Mockito 测试 |
| `route_skill` | 判断代码场景(Controller/Mapper/Service/...) |
| `list_skills` | 列出所有可用评审场景及清单 |

## Claude Code 客户端配置

编辑 `~/.config/claude-code/mcp.json`:

```json
{
  "mcpServers": {
    "ai-code-reviewer": {
      "url": "http://localhost:8080/sse",
      "transport": "sse"
    }
  }
}
```

重启 Claude Code 后,在对话中输入:

> 用 review_code 工具评审这段代码:[贴代码]

Claude Code 会自动调用本 MCP Server。

## Cursor / Cline 配置类似

参考各 IDE 文档的 MCP server 配置项,填 `http://localhost:8080/sse` 即可。

## 命令行验证(不依赖 IDE)

用 `mcp-inspector` 工具:
npx @modelcontextprotocol/inspector http://localhost:8080/sse

打开浏览器看到的 UI 里能浏览所有 Tools 并直接调用测试。