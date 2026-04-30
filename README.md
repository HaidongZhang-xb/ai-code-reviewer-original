# AI Code Reviewer (Day 1)

基于 Spring AI Alibaba 的 Java 智能代码评审系统 - Gitee 版

## 一、准备工作

### 1. 申请 DashScope(通义千问) API Key

1. 打开 https://bailian.console.aliyun.com/
2. 用阿里云账号登录,新用户有免费额度
3. 顶部菜单 → API-KEY → 创建 API-KEY
4. 复制 sk-xxx 形式的 key

### 2. 申请 Gitee 个人访问令牌

1. 登录 Gitee → 右上角头像 → 设置 → 私人令牌
   或直接打开 https://gitee.com/profile/personal_access_tokens
2. 点击"生成新令牌"
3. 权限至少勾选: `pull_requests`, `notes`, `projects`(只读), `user_info`
4. 复制生成的 token(只显示一次!)

### 3. 准备一个测试仓库

在 Gitee 上新建一个仓库,例如 `my-test-repo`,任意丢一两个 Java 文件进去。

### 4. 内网穿透(让 gitee.com 能访问到你的 localhost:8080)

推荐 cpolar(Gitee 国内访问稳定):

1. 注册 https://www.cpolar.com/
2. 下载客户端,运行: `cpolar http 8080`
3. 复制控制台显示的 https 域名,例如 `https://abc123.r1.cpolar.top`

也可以用 ngrok / 花生壳 / frp。

## 二、配置环境变量

```bash
export DASHSCOPE_API_KEY=sk-你的key
export GITEE_ACCESS_TOKEN=你的gitee令牌
export GITEE_WEBHOOK_PASSWORD=my-secret-pwd-12345
```

或直接改 `src/main/resources/application.yml` 里的默认值。

## 三、启动

```bash
mvn spring-boot:run
```

看到 `Started ReviewerApplication` 就是起来了,默认端口 8080。

测试一下:
```bash
curl http://localhost:8080/webhook/ping
# {"status":"ok","service":"ai-code-reviewer"}
```

## 四、配置 Gitee Webhook

1. 进入测试仓库 → 管理 → WebHooks → 添加 webHook
2. URL 填: `https://abc123.r1.cpolar.top/webhook/gitee`(用你的内网穿透地址)
3. WebHook 密码: `my-secret-pwd-12345`(和 application.yml 里一致)
4. 事件勾选: **Pull Request**
5. 保存

## 五、触发测试

1. 在测试仓库新建一个分支,改一个 Java 文件,提交并 push
2. 在 Gitee 上发起 Pull Request
3. 观察控制台日志,应该能看到:
   - 收到 Webhook
   - 拿到 diff
   - LLM 返回的评审结果
4. 在 Gitee PR 页面也能看到一条 "🤖 AI 代码评审报告"

## 六、常见问题

**Q: Webhook 鉴权失败?**
A: 检查 application.yml 的 webhook-password 和 Gitee 后台填的密码是否一致。

**Q: 拿不到 diff,403?**
A: 检查 Gitee Token 权限是否勾了 pull_requests,以及仓库是否私有(私有需要 token 有该仓库的访问权限)。

**Q: LLM 返回不是合法 JSON?**
A: 把日志级别开到 DEBUG,看 ReviewService 里打印的 LLM 原始返回。可能是模型版本问题,试试改 application.yml 的 model 为 qwen-max。
