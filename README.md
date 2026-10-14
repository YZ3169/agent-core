# agent-core

一个自己手写推理循环的轻量级 ReAct Agent 服务：**工具注册、循环编排、失败回灌、会话记忆、SSE 流式**全部自己实现，
Spring AI 在这里只承担一件事——把 Prompt 发出去、把文本收回来。

> **关于 AI 辅助研发**
> 本项目的代码由 Claude Code 按我给出的设计约束生成，我在其基础上逐文件阅读、调试、修改并跑通全部验证。
> **这不是"我一行行手敲的"项目**，如果你在面试里问起，我会如实说明，并且能解释其中任何一段代码为什么这么写。
>
> 另外如实说明两点：
> 1. **真实 DeepSeek 链路未被验证过**——开发机环境里没有 `DEEPSEEK_API_KEY`，我没能跑一次真实的模型调用。
>    所有测试用的是一个按剧本返回预置文本的 `FakeChatModel`。怎么在 30 秒内补上这次验证，见下面「已知限制」。
> 2. 迭代过程中 `SqlGuard` 和 `mvn` 报的编译错误是我看着报错改的，不是一次写对的。

---

## 它解决什么问题

一个"能自己查数据、自己算数、自己读文件"的问答服务，麻烦的地方从来不是调模型 API，而是**模型不听话**：

| 麻烦 | 后果 | 本项目的做法 |
|---|---|---|
| 模型在两个工具之间来回打转 | 无限循环，token 烧光 | `max-steps` 硬上限，到顶就停，返回结构化状态而不是假装答完了 |
| 模型输出格式飘了 | JSON 解析失败，整轮 500 | 解析失败**当成一次观察回灌**，让模型自己改，不中断整轮 |
| 工具报错 | 异常冒泡，用户看到 500 | 异常信息翻译成 `Observation` 发回给模型，它有第二次机会 |
| 模型让工具 `DROP TABLE` | 数据没了 | 语法层白名单 + JDBC 层行数/超时兜底，两道闸 |
| 模型传了 `../../application.yml` | 任意文件读取漏洞 | 路径规范化 + 真实路径复核，两次包含判定 |
| 上下文无限膨胀 | 越聊越贵，最后超长报错 | 按字符预算滑动裁剪，保留最新 |

核心主张：**这些都不是"调个框架就自动有"的**。Spring AI 的 `ChatClient` + `@Tool` 能把上面的坑全盖住，
代价是"循环怎么转"变成黑盒。这个项目反过来，把每一处都摊开写，每一处都能单独测。

---

## 架构

```
                    POST /agent/chat  (SSE)      POST /agent/chat/sync      GET /agent/trace/{id}
                            │                            │                          │
                            ▼                            ▼                          ▼
                    ┌───────────────────────────────────────────────┐        ┌──────────────┐
                    │              AgentController                  │        │  TraceStore  │
                    │   SSE 用一个独立线程池把整轮跑在后台            │───────▶│ (内存, 回放) │
                    └───────────────────────┬───────────────────────┘        └──────────────┘
                                            │                                        ▲
                                            ▼                                        │ 每次工具调用写一条
                    ┌───────────────────────────────────────────────┐                │
                    │                ReActAgent                     │────────────────┘
                    │            （本项目的核心，手写）              │
                    │                                               │
                    │   while (step < maxSteps) {                   │
                    │     ① 取 ChatModel ── 没有? → 返回 1001 降级   │
                    │     ② Prompt = 系统提示词 + 会话记忆           │
                    │     ③ 带超时地调用模型                        │
                    │     ④ ActionParser 解析输                     │                      ┌──────────────┐
                    │     ⑤ Final Answer? → 结束                    │                      │  ChatModel   │
                    │        Action?       → 执行工具 → 回灌        │─────────────────────▶│  (Spring AI) │
                    │        解析失败?     → 回灌错误原因           │   Prompt 进 / 文本出  └──────┬───────┘
                    │   }                                           │                             │
                    └───┬─────────────────┬─────────────────┬───────┘                             │
                        │                 │                 │                    ┌────────────────▼──────────────┐
                        ▼                 ▼                 ▼                    │  ChatModelConfig:              │
              ┌──────────────┐  ┌──────────────┐  ┌──────────────┐               │  @Conditional(有非空 api-key)   │
              │ToolRegistry  │  │Conversation  │  │SystemPrompt  │               │  → OpenAiChatModel            │
              │              │  │Memory        │  │Builder       │               │    base-url = api.deepseek.com │
              │ 自动收集所有  │  │              │  │              │               │    没配 Key → 这个 Bean 不存在  │
              │ @Component    │  │ 按 sessionId │  │ 把工具清单    │               └────────────────────────────────┘
              │ 的 AgentTool  │  │ 隔离 + 按字符 │  │ 渲染进提示词  │
              └──────┬───────┘  │ 预算滑动裁剪  │  └──────────────┘
                     │          └──────────────┘
        ┌────────────┼────────────┐
        ▼            ▼            ▼
 ┌────────────┐┌────────────┐┌────────────┐
 │ sql_query  ││ file_read  ││ calculator │
 │            ││            ││            │
 │ SqlGuard   ││ 白名单根   ││ 手写递归   │
 │ 只读白名单  ││ 目录 + 两次││ 下降解析器 │
 │ +setMaxRows││ 包含判定   ││ BigDecimal │
 └─────┬──────┘└────────────┘└────────────┘
       ▼
  H2 内存演示库 (sales_order)
```

### ReAct 协议（提示词层约定）

```
Thought: 我需要先查一下各区域的销售额
Action: sql_query
Action Input: {"sql": "SELECT region, SUM(amount) AS total FROM sales_order GROUP BY region LIMIT 10"}
                                          ↓ 工具执行结果以这个前缀回灌
Observation: 查询成功，返回 5 行 ...
                                          ↓
Thought: 华东最高，可以回答了
Final Answer: 华东区销售额最高，为 29.5 万元。
```

选文本协议而不是 function calling 的结构化字段，是因为"怎么解析、解析不了怎么办"本身是这个项目要展示的能力。
`ActionParser` 处理了模型的常见不听话行为：加 ```json 代码块、大小写混用、只写 Action 不给 Input、
既写 Action 又写 Final Answer（以 Final Answer 为准，避免多跑一次工具）。

---

## 内置工具

| 工具 | 作用 | 安全措施 |
|---|---|---|
| `sql_query` | 只读查询 H2 演示库的 `sales_order` 表 | 只允许单条 SELECT；必须显式写 `LIMIT`；拒绝 DDL/DML/多语句/注释绕过；JDBC 层 `setMaxRows` 硬截断 + `setQueryTimeout` |
| `file_read` | 读白名单目录下的文本文件 | 路径 `normalize()` 后判包含（挡住 `..` 与绝对路径）；`toRealPath()` 再判一次（挡住软链接绕过）；大小上限 |
| `calculator` | 四则运算表达式求值 | 手写解析器，只认数字与 `+ - * / % ( )`，不引入可执行任意代码的脚本引擎；BigDecimal 避免浮点噪声 |

新增工具只需要写一个 `AgentTool` 实现类并标 `@Component`——`ToolRegistry` 会自动收集，
系统提示词里的工具清单也跟着自动更新，**不需要改任何已有代码**。

---

## 快速开始

### 1. 没有 API Key 也能跑（这是刻意设计的）

```bash
cd /d/Projects/agent-core
mvn clean package
java -jar target/agent-core.jar
```

服务正常启动后，接口会返回**结构化错误码而不是 500**：

```bash
curl -X POST http://localhost:8080/agent/chat/sync \
     -H "Content-Type: application/json" \
     -d '{"sessionId":"s1","message":"你好"}'
```

```json
HTTP/1.1 503 Service Unavailable
{"sessionId":"s1","status":"ERROR","steps":0,
 "errorCode":"1001","errorMessage":"未配置模型 API Key（DEEPSEEK_API_KEY），Agent 能力已降级",
 "toolCalls":[],"durationMs":0}
```

用 503 而不是 500：**"服务是好的，只是模型没配"和"服务内部炸了"是两件事**，
调用方（和监控告警）需要能分清。启动期也不会因为缺凭据而失败——`ChatModel` 这个 Bean
只在 `spring.ai.openai.api-key` 非空时才存在，由 `OnApiKeyPresentCondition` 决定。

### 2. 配上 Key 跑真实链路

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx        # 只从环境变量读，代码里没有默认值、不落盘
java -jar target/agent-core.jar
```

配好之后可以试着问（Windows 上中文写成 `-d` 参数容易被 GBK 编码搞乱，建议放进文件再 `--data-binary @file`）：

```bash
printf '%s' '{"sessionId":"demo","message":"哪个区域的订单金额最高？先查库再回答"}' > req.json

# 流式：-N 关闭缓冲，能直接看到逐条推上来的 SSE 事件
curl -N -X POST http://localhost:8080/agent/chat \
     -H "Content-Type: application/json" --data-binary @req.json

# 同步：一次拿完整结果
curl -X POST http://localhost:8080/agent/chat/sync \
     -H "Content-Type: application/json" --data-binary @req.json

# 回放这次运行的调用链
curl http://localhost:8080/agent/trace/demo
```

### 3. 跑测试

```bash
mvn test
```

**99 个测试全部不依赖网络、不需要 API Key**，靠 `FakeChatModel`（实现 Spring AI 的 `ChatModel` 接口，
按剧本返回预置字符串）驱动整个循环。

---

## 接口说明

### `POST /agent/chat` — 流式（SSE）

请求体 `{"sessionId": "可选", "message": "必填"}`。`sessionId` 省略时服务端生成并在首个事件里可查（trace 可用）。

按事件类型推送，客户端按 `event:` 名分流：

| 事件名 | 时机 | data |
|---|---|---|
| `thought` | 模型每轮推理后 | `{"step":1,"text":"..."}` |
| `tool_call` | 决定调工具，**尚未执行** | `{"step":1,"tool":"calculator","arguments":{...}}` |
| `observation` | 工具执行完（成功或失败都推） | `{"step":1,"tool":"calculator","text":"..."}` |
| `answer` | 最终答案（达到 max-steps 时也会推一条兜底文案） | `{"text":"..."}` |
| `error` | 运行失败 | `{"code":"1001","message":"..."}` |

```
event:thought
data:{"step":1,"text":"需要先算一下提成"}

event:tool_call
data:{"step":1,"tool":"calculator","arguments":{"expression":"(128000+39800)*0.06"}}

event:observation
data:{"step":1,"tool":"calculator","text":"(128000+39800)*0.06 = 10068"}

event:answer
data:{"text":"提成是 10068 元。"}
```

### `POST /agent/chat/sync` — 同步

同样的请求体，一次性返回完整结果。成功和失败**共用同一个响应结构**，调用方只需认识一种 JSON。

```json
{
  "sessionId": "s1",
  "status": "COMPLETED",
  "answer": "华东区销售额最高。",
  "steps": 2,
  "errorCode": null,
  "errorMessage": null,
  "toolCalls": [
    {"step":1,"tool":"sql_query","arguments":{"sql":"SELECT ... LIMIT 10"},
     "durationMs":4,"success":true,"resultSummary":"查询成功，返回 5 行"}
  ],
  "durationMs": 1832
}
```

`status` 有三个取值：`COMPLETED`（答完了）、`MAX_STEPS_EXCEEDED`（护栏生效，HTTP 仍是 200）、`ERROR`（失败）。

### `GET /agent/trace/{sessionId}` — 回放调用链

返回该会话最近一次运行的完整轨迹：每一步的模型耗时、送进去多少字符、每次工具调用的
**名称 / 入参 / 耗时 / 结果摘要 / 成功与否**。没有记录时返回 404（而不是空对象——
空对象分不清"没跑过"和"跑过但没调工具"）。

### 错误码

| 码 | HTTP | 含义 |
|---|---|---|
| `1001` | 503 | 未配置模型 API Key，能力降级。**服务是好的，配了就能用** |
| `1002` | 504 | 单步超时（模型调用或工具执行超过 `agent.step-timeout-ms`） |
| `1003` | 200 | 达到 max-steps 仍未收敛。护栏，不是崩溃 |
| `1004` | 502 | 模型调用失败（网络/鉴权/限流） |
| `1005` | 400 | 请求参数不合法 |
| `9999` | 500 | 未预期的内部错误（有日志堆栈） |

---

## 可配置项（`agent.*`，全部有默认值）

```yaml
agent:
  max-steps: 6              # 循环硬上限
  step-timeout-ms: 30000    # 单步超时（模型调用 + 工具执行）
  memory:
    max-chars: 8000         # 会话历史字符预算，超了从头丢
  tools:
    sql:
      max-rows: 50
      query-timeout-seconds: 5
    file-read:
      root: data/agent-files
      max-bytes: 65536
```

---

## 测试覆盖

`mvn test` 实测输出（2026-10-14）：

```
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- ActionParserTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -- ReActAgentTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- ConversationMemoryTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- CalculatorToolTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- FileReadToolTest
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 -- SqlGuardTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- SqlQueryToolTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- ToolRegistryTest
Tests run:  4, Failures: 0, Errors: 0, Skipped: 0 -- TraceStoreTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- AgentControllerTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -- AgentDegradationTest
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

重点覆盖的不是"happy path 能跑"，而是**出问题时行为是否可预期**：

- `ReActAgentTest`：maxSteps 到顶时**模型恰好被调用 maxSteps 次**（多给 10 条剧本，断言多余的没被消费）；
  工具抛异常时断言**异常文本确实出现在下一轮的 Prompt 里**（不只是"没崩"）；
  单步超时抛 `1002`；没有 ChatModel 时返回 `1001` 而不是抛异常。
- `SqlGuardTest`：DDL/DML/多语句/注释绕过/`SELECT INTO` 一律拒绝；
  同时断言**不误伤**——字符串字面量里的 `'drop table'`、含关键字的列名 `created_at` 都要放行。
- `FileReadToolTest`：目录穿越用例会**先把越界目标文件真的建出来**，
  否则无法区分"被安全策略拦住"和"文件恰好不存在"。
- `AgentDegradationTest`：把 api-key 显式置空后拉起完整 Spring 上下文——
  **这个测试类能跑起来本身就是结论的一半**。

---

## 已知限制与取舍（如实列出）

1. **真实 DeepSeek 链路未验证**。开发机没有 `DEEPSEEK_API_KEY`，我只验证了
   "没有 Key 时的降级行为"和"用假模型时的循环行为"，**没有跑过一次真实的模型调用**。
   补验证只需要一条命令（配好 Key 后）：

   ```bash
   DEEPSEEK_API_KEY=sk-xxx mvn spring-boot:run
   # 另开一个终端：
   curl -s -X POST http://localhost:8080/agent/chat/sync -H "Content-Type: application/json" \
        -d '{"message":"帮我算 (128000+39800)*0.06，然后用 calculator"}'
   ```

   风险点在**模型的输出格式**：`deepseek-chat` 不保证每次都严格输出 `Thought/Action/Action Input`。
   真跑起来若格式经常飘，会表现为 `steps` 偏高（每轮格式错误都要多花一步纠正），
   而不是直接失败——这正是 `INVALID → 回灌` 那条路径存在的意义。

2. **`SqlGuard` 是正则层面的白名单，不是 SQL 语法分析器**。它能挡住最现实的一类问题
   （`DROP`/`DELETE`/多语句/注释绕过），但**不是可靠的唯一边界**。真上线应该给这个连接
   一个只有 SELECT 权限的数据库账号——那才是数据库侧的硬边界。这里是纵深防御靠前的一层。

3. **`sql_query` 查的是内存 H2 演示库**，不是真实业务库。选它是为了让项目 clone 下来零依赖就能跑
   （不引入 MySQL/Docker）。换真实库只需要换 `spring.datasource` 配置，工具代码不用动。

4. **会话记忆与 trace 都在内存里**，重启即丢。取舍理由：它们是会话级、短期、可重建的数据，
   为它们引入一个数据库带来的运维成本大于收益。多实例部署时记忆不共享，需要换成 Redis——
   这是当前版本的明确边界。

5. **记忆裁剪是单条粒度，不成对**。极长会话被裁到中途时，可能出现"Observation 还在、
   产生它的 Action 已丢"的残缺片段。因为每条 Observation 是自包含文本，不会让模型读不懂，
   只是少了一点推理链上下文。比"一刀切清空历史"体验好。

6. **`spring-ai-openai` 而不是 `spring-ai-starter-model-openai`**。starter 带一整套自动装配，
   没有 Key 时可能在启动期被它的断言拦住，与"无 Key 必须能启动"冲突。用核心库 +
   自己按条件装配，代价是 `ChatModelConfig` 里多 20 行，换来的是启动行为完全可控。

7. **不支持并行工具调用**。一轮一个 Action。ReAct 原始形式如此，且串行让 trace 和超时语义都简单得多。

---

## 目录结构

```
src/main/java/com/yuzhuo/agent/
├── AgentCoreApplication.java
├── agent/                       ← 手写的循环
│   ├── ReActAgent.java          ← 核心：循环、护栏、异常回灌
│   ├── AgentListener.java       ← 过程事件出口（SSE 和测试都从这观察）
│   ├── AgentRequest/Response/Status.java
│   ├── parse/                   ← ActionParser / ParsedAction / ActionType
│   └── prompt/SystemPromptBuilder.java
├── tool/
│   ├── AgentTool.java           ← 扩展点：加工具只需实现它
│   ├── ToolRegistry.java        ← 自动收集 + 渲染 + 执行
│   └── builtin/                 ← SqlQueryTool / SqlGuard / FileReadTool / CalculatorTool
├── memory/ConversationMemory.java
├── trace/                       ← TraceStore / TraceSession / ToolCallRecord / ModelCallRecord
├── config/                      ← AgentProperties / ChatModelConfig / OnApiKeyPresentCondition / 线程池
├── error/                       ← AgentErrorCode / AgentException
└── web/                         ← AgentController / SseAgentListener / GlobalExceptionHandler
```

技术栈：Java 17 · Spring Boot 3.5.16 · Spring AI 1.1.8 · H2（内存）· Maven。
模型走 DeepSeek（OpenAI 兼容协议），`spring.ai.openai.api-key: ${DEEPSEEK_API_KEY:}` —— 无默认值、不落盘。
