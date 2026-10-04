# call-graph-java

本地源码方法级调用图的构建、增量维护与查询服务。输入仅来自仓库内的 `.java` 源码
（使用 JavaParser 在本地完成解析，不依赖任何外部服务）。

## 功能概览

- **全量构建**：从仓库源码建立类型、方法与方法间调用边。
- **虚调用解析**：对 `receiver.method()` 按 Class Hierarchy Analysis（CHA）给出沿类型
  层次可推导的**全部具体目标方法**（含抽象基类中已实现的方法），接口方法等无方法体的
  声明不会作为目标。
- **增量更新**：单文件或类型层次变化后，仅重新解析受影响文件并整体重算解析结果，
  其结果与全量重建逐项一致；删除文件/类型后旧调用边不会残留。
- **快照隔离**：查询与更新可并发。已提交图为不可变快照，查询线程始终看到某一次提交的
  完整图，绝不会读到半更新状态。
- **环与 SCC**：Tarjan 强连通分量、环上方法归类、跨包调用边、递归判定与可达性查询。
- **可取消**：构建与增量更新接受取消令牌；无论取消发生在开始前、进行中还是提交前一刻，
  被取消的变更都不提交、不落盘、不留下任何可恢复记录，已持久化状态保持不变。
- **重启恢复**：服务每次启动自动恢复到最近一次已提交的完整状态，所有查询接口在启动
  完成后立即可用，无需任何重建请求；快照已是最新时启动不会触发全量解析或全量构建。
- **崩溃恢复**：快照“临时文件 + fsync + 原子改名”写入；变更以 WAL 日志记录提交点，
  重启后从最近快照继续并只应用已提交但未并入快照的变更，不重复、不遗漏、无部分写入。
- **仓库内目标**：所有调用边的目标都是仓库源码中实际声明的方法或构造器；对仓库外类型
  （如 `java.util.ArrayList`）的构造或方法调用不产生任何调用边。

## 解析语义

调用点分类：

- `unqualified()`、`this.m()`：按当前类处理（虚分派 / 构造器互调）。
- `super.m()` / `super(...)`：沿继承链向上解析到最近的已声明目标，边标记为 `SUPER`。
- `Type.m()`（大写开头的静态类型作用域）：`STATIC`，直接解析到声明方法。
- `new T(...)`：`CONSTRUCTOR`，直接解析到 `T` 的构造器。
- `expr.m()`：先推断 `expr` 的静态类型（方法参数、局部变量、`new` 初始化、字段、
  属性/无参 getter 链），再做 CHA 虚分派。

CHA 虚分派：对静态接收者类型 `T` 及其所有可赋值子类型 `S`，按虚分派规则（自 `S`
沿父类链，再到接口默认方法）找到 `S` 上实际执行的具体实现；不同 `S` 分派到同一
实现时去重。这是一种**上界近似**：它给出“类型层次上可能”的全部目标，不做指针敏感
的可达类型收窄，因此可能包含运行时不会执行的目标，但不会遗漏可推导目标。

方法标识：`owner#name(参数全限定名, ...)`；构造器名为 `<init>`。按名字与参数个数匹配
重载。简单类型名依据显式 import、同包类型、唯一同名类型与 `java.lang` 常见类型归一化。

## 增量一致性

`IncrementalUpdater` 以“变更文件集合”为输入：

1. 用新解析结果替换（或删除）这些文件的原始解析产物；
2. 重新建立全局类型表、名称解析、层次与方法表；
3. 对全部调用点重新解析（类型层次变化的影响因此自动传播，无需手工打补丁式删边）。

因此增量结果的 `types/methods/edges` 集合等于对同一目录全量重建的结果；自动化测试
`IncrementalConsistencyTest` 对修改与删除场景做了逐项比对。

## 重启恢复与 revision 单调性

服务在启动时（Web 端口开放之前）自动执行恢复：载入最近快照，再按顺序回放 revision
更新且已有 `COMMIT` 的 `BEGIN` 记录。恢复完成后所有查询接口立即可用，返回内容与重启前
完全一致（`GET /api/callgraph/snapshot` 的 revision 与各计数不变），无需客户端再次触发
全量构建。当快照已包含全部已提交变更且没有待回放记录时，启动只读取快照文件，不触发
任何全量解析或全量构建。

revision 在所有提交与重启之间**严格单调递增**：每次提交的 revision 取
“内存中当前 revision、磁盘快照 revision、日志中已提交 revision”三者的最大值加一，
因此重启后的第一次构建或增量更新绝不会回退到更小的 revision，也不会覆盖磁盘上已持久化
的更高版本快照。

## 取消语义

构建与增量更新在解析每个文件时以及**提交前最后一刻**检查取消令牌：

- 取消发生在开始前、进行中或提交前一刻：变更以 `CancellationException` 终止，不提交、
  不落盘；日志中的 `BEGIN` 记录被清除，临时快照文件被删除，状态目录不残留任何临时
  产物；重启后 revision 与图内容与取消前完全一致。
- 取消到达时变更已经提交完成：已提交状态不被回滚、不被污染，取消请求按“已完成”
  处理（见 HTTP 接口的 `alreadyCompleted`）。

## 持久化与崩溃恢复

状态目录（默认 `.callgraph-state/`）：

- `snapshot.json.tmp` → fsync → 原子改名为 `snapshot.json`，任何时刻崩溃都不会留下
  半个正式快照；
- `journal.log` 为只追加 WAL：变更开始时写 `BEGIN{revision,files}` 并 fsync；快照原子
  落盘后写 `COMMIT{revision}` 并 fsync（提交点）；随后发布到内存并裁剪日志。日志重写
  （裁剪、丢弃、清理）同样走“临时文件 + fsync + 原子改名”。

启动时 `recover()`：先删除残留的 `snapshot.json.tmp`，再载入最近快照，然后按顺序回放
revision 更新且已有 `COMMIT` 的 `BEGIN`，最后清除没有 `COMMIT` 的残留 `BEGIN`（来自
被取消或崩溃中断的变更）。进程在变更提交前被强制终止时，未完成的变更不会被应用，
只恢复到最近一次已提交状态，且残留记录不会导致后续 revision 错乱。

## 并发模型

- 图保存在 `AtomicReference<GraphSnapshot>`，`GraphSnapshot` 全部字段为不可变集合；
  发布即整体可见。
- 更新之间由单一锁串行化，查询无锁，基于引用快照计算，天然可取消且互不阻塞。
- 构建在独立线程异步执行，构建与更新执行期间查询与取消接口保持可用。

## 性能边界

- **内存**：解析逐文件进行，JavaParser AST 在用完后即随单次解析释放，不保留整库 AST；
  常驻数据为紧凑的类型/方法/调用点/边元数据，规模与“声明数 + 调用点数 + 边数”线性。
  边为 CHA 上界，最坏情况下可能接近“调用点数 × 层次规模”。
- **时间**：单次解析随源码大小线性；CHA 目标查找在方法表上按类型链遍历。
- **查询**：可达性 / SCC 在快照的方法图上即时计算，复杂度约为
  `O(V + E)`（Tarjan / BFS），大仓库建议使用带深度上界的可达性入口。
- 不做过程间指针分析与全限定名推断之外的泛型/类型推断，换取线性规模的元数据占用；
  无法静态确定接收者类型的调用点不产生边（可在后续扩展中增强推断）。

## HTTP 接口

- `POST /api/callgraph/build` 异步启动全量构建，立即返回
  `{buildId, status: "running", revision}`（revision 为当前在服务的快照版本；
  构建产生的新版本由构建状态接口报告）
- `POST /api/callgraph/update` 增量更新，返回 `{revision}`
- `GET  /api/callgraph/builds/{buildId}` 构建状态：
  `{buildId, status, revision, error?}`，status ∈ `running|completed|cancelled|failed`
- `POST /api/callgraph/builds/{buildId}/cancel` 请求取消：
  - 构建进行中 → `200 {"status": "cancelRequested"}`，构建尽快中止，之后状态变为
    `cancelled`，查询仍返回上一个完整快照；
  - 构建已完成 → `409 {"status": "alreadyCompleted"}`（已取消/已失败分别为
    `alreadyCancelled`/`alreadyFailed`），已提交状态不受影响；
  - 未知 buildId → `404`。
- `GET  /api/callgraph/snapshot` 图规模与 revision
- `GET  /api/callgraph/methods/{owner}/{name}` 出边（虚调用含全部候选）
- `GET  /api/callgraph/callers/{owner}/{name}` 入边
- `GET  /api/callgraph/reachable/{owner}/{name}` 可达集合
- `GET  /api/callgraph/metrics/{owner}/{name}` 度数/SCC/递归/可达性
- `GET  /api/callgraph/cycles`、`/scc`、`/cross-package`

## 配置

`application.properties`：

```
callgraph.repo-path=.
callgraph.state-path=.callgraph-state
callgraph.build-on-startup=false
```

## 测试

```
mvn test
```

覆盖：虚调用 CHA 候选、增量与全量一致性（含删除类型清边）、并发查询只见完整快照、
环/SCC/自环、跨包归类、大仓库构建，以及：

- **重启恢复**（`RestartRecoveryTest`）：通过真实的服务启动/停止（Spring 应用上下文
  随端口一起启停）验证重启前后 snapshot 的 revision 与各计数一致、查询结果一致、
  revision 跨重启严格递增；
- **取消一致性**（`CancellationAndRecoveryTest`）：覆盖开始前、进行中、提交前一刻
  取消与取消已完成的变更，验证被取消变更不留任何可恢复记录、状态目录无临时产物、
  重启后 revision 与图内容与取消前一致；
- **HTTP 取消**（`HttpCancellationTest`）：长构建在完成前即可拿到 buildId 并被成功
  取消（状态变为 `cancelled`，快照保持上一已提交版本），取消已完成构建返回可区分的
  `409 alreadyCompleted`；
- **崩溃恢复**（`CrashRecoveryTest`）：在独立 JVM 中构建到提交前一刻强制 `halt`
  （等价 `kill -9`），重启后未提交变更不被应用、残留日志被清理、后续 revision 正常
  递增；
- **仓库外调用目标**（`ExternalCallTargetTest`）：样例源码含
  `new java.util.ArrayList<>()` 等 JDK 类型构造/方法调用，验证不产生指向仓库外类型的
  调用边，且仓库内构造器边保持不变。
