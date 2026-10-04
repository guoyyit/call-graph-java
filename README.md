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
- **可取消**：构建与增量更新接受取消令牌；HTTP 构建异步执行，客户端在构建完成前即可
  获得 buildId 并随时取消。取消以异常终止且不提交、不落盘，已持久化状态保持不变。
- **崩溃恢复**：快照“临时文件 + fsync + 原子改名”写入；变更以 WAL 日志记录提交点，
  重启后从最近快照继续并只应用已提交但未并入快照的变更，不重复、不遗漏、无部分写入。
- **重启自恢复**：服务每次启动自动恢复最近一次已提交的完整状态，查询接口在启动完成后
  立即可用，无需客户端重新触发构建；revision 在提交与重启之间严格单调递增。

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

调用边的目标只限于仓库内源码**实际声明**的方法与构造器。对仓库外类型（如
`new java.util.ArrayList<>()`）的构造或方法调用不产生任何调用边；仓库内声明的方法与
构造器边保持不变。

## 增量一致性

`IncrementalUpdater` 以“变更文件集合”为输入：

1. 用新解析结果替换（或删除）这些文件的原始解析产物；
2. 重新建立全局类型表、名称解析、层次与方法表；
3. 对全部调用点重新解析（类型层次变化的影响因此自动传播，无需手工打补丁式删边）。

因此增量结果的 `types/methods/edges` 集合等于对同一目录全量重建的结果；自动化测试
`IncrementalConsistencyTest` 对修改与删除场景做了逐项比对。

## 持久化与崩溃恢复

状态目录（默认 `.callgraph-state/`）：

- `journal.log` 为只追加 WAL。每次变更先写 `BEGIN{revision,files}` 并 fsync，计算完成
  后先写临时快照（`snapshot.json.tmp` + fsync），再写 `COMMIT{revision}` 并 fsync
  （**提交点**），最后原子改名为 `snapshot.json` 并发布到内存；
- 快照发布后裁剪日志。任何时刻崩溃都不会留下半个正式快照。

启动时自动执行 `recover()`（由 `ApplicationRunner` 在服务就绪前完成）：

1. 删除残留的 `snapshot.json.tmp`；
2. 载入最近快照；
3. 按顺序回放 revision 更新且已有 `COMMIT` 的 `BEGIN`（崩溃发生在 COMMIT 之后、快照
   改名之前的情形），回放结果再次走完整提交流程落盘；
4. 丢弃只有 `BEGIN` 没有 `COMMIT` 的未完成记录（崩溃或被取消变更的残留），并裁剪
   不新于当前快照的日志。

崩溃时机与结果：崩溃在 COMMIT 之前——变更如同从未发生，重启后恢复到最近一次已提交
状态；崩溃在 COMMIT 之后——变更已提交，重启后通过回放或快照看到它。已保证不重复应用、
不跳过变更、无部分写入。当快照已包含全部已提交变更且没有待回放记录时，启动不会触发
任何全量解析或全量构建。

## 取消语义

- 取消在**开始前**到达：变更直接以取消异常结束，不写任何日志记录。
- 取消在**进行中**到达：计算在下一个检查点抛出 `CancellationException`，已写入的
  `BEGIN` 记录被清除，状态目录不留临时产物，内存与磁盘保持取消前的已提交状态。
- 取消在**提交前一刻**到达：提交段入口统一再检查一次取消标记，尚未越过提交点则中止
  并清理，与“进行中取消”完全一致。
- 取消在**提交完成后**到达：取消不生效，已提交状态不会被回滚或污染。

无论取消发生在哪个时机，重启后 revision 与图内容都与取消前完全一致；被取消的变更不会
留下任何可被恢复的记录。revision 在所有提交与重启之间严格单调递增：每次提交取
`max(当前内存 revision, 日志中最大已提交 revision) + 1`，增量更新基于恢复后的最新
状态计算，不会回退到更小的 revision，也不会覆盖已持久化的更高版本数据。

## 并发模型

- 图保存在 `AtomicReference<GraphSnapshot>`，`GraphSnapshot` 全部字段为不可变集合；
  发布即整体可见。
- 更新之间由单一锁串行化，查询无锁，基于引用快照计算，天然可取消且互不阻塞。

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

- `POST /api/callgraph/build` 异步发起全量构建，立即返回
  `{"buildId": "...", "status": "running"}`；构建在后台执行期间，查询与取消接口保持
  可用，且查询始终返回上一个完整快照。
- `GET /api/callgraph/builds/{buildId}` 查询构建状态：
  `status` 为 `running` / `completed` / `cancelled` / `failed` 之一；`completed` 时
  附带 `revision`，`failed` 时附带 `error`。未知 buildId 返回 404。
- `POST /api/callgraph/builds/{buildId}/cancel` 请求取消：
    - 构建进行中 → `200 {"status": "cancelRequested"}`，构建尽快中止，随后状态变为
      `cancelled`，图保持上一个完整快照；
    - 构建已完成 → `409 {"status": "alreadyCompleted"}`（可区分结果，不静默忽略）；
    - 已取消 / 已失败 → `409 {"status": "alreadyCancelled" | "alreadyFailed"}`；
    - 未知 buildId → `404 {"status": "notFound"}`。
- `POST /api/callgraph/update` 增量更新（同步），返回 `{"revision": ...}`
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
环/SCC/自环、跨包归类、构建取消不破坏状态、WAL 崩溃恢复不重放不漏用、大仓库构建。

本轮新增的行为验证：

- `ServiceLifecycleRecoveryTest`：通过真实的服务启动/停止流程（Spring 应用上下文 +
  HTTP）验证重启后 revision 与各计数一致、查询结果与重启前相同、启动不重写快照、
  revision 跨重启单调递增。
- `CancellationTimingTest`：取消发生在开始前、进行中、提交前一刻三个时机均不留可恢复
  记录、不留临时产物，重启后状态与取消前一致；取消已提交的变更不回滚。
- `HttpBuildCancellationTest`：长构建进行中可通过 buildId 成功取消并观察到
  `cancelled` 结果，期间查询接口可用且返回上一个完整快照；取消已完成构建返回
  `409 alreadyCompleted`；未知 buildId 返回 404。
- `CrashRecoveryTest`：fork 独立 JVM 在构建中途 `Runtime.halt` 模拟强制终止，重启后
  只恢复最近一次已提交状态，残留 BEGIN 记录被清理，后续 revision 不错乱。
- `ExternalConstructorCallTest`：含 `new java.util.ArrayList<>()` 的样例源码不产生
  指向 JDK 类型的调用边，仓库内构造器边保持正确。
