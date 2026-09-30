# call-graph-java

本地源码方法级调用图构建与查询服务。全部输入来自被分析仓库内的 Java 源码，
不依赖任何外部服务；运行期只需要本地文件系统。

- 直接调用（静态、私有、构造器、`super`）与虚调用（`invokevirtual`/`invokeinterface`）均解析；
- 虚调用按**类层次分析（CHA）**给出「接收者静态类型在仓库内全部可分配子类型」上的全部候选目标；
- 文件级增量更新，增量结果与全量重建严格一致，不保留过期边；
- 单写者 + 不可变快照（MVCC），查询永远看到某一已提交时刻的完整状态；
- 递归检测（迭代式 Tarjan SCC）、跨包边归类、可达性/调用链查询；
- 构建与查询协作式可取消；WAL + 原子快照提交保证崩溃恢复 exactly-once。

## 构建与运行

```bash
mvn -o test          # 离线运行全部 21 个测试
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--callgraph.repo=/path/to/repo --callgraph.data=./.callgraph-data"
```

配置项（`application.properties` 或系统属性）：

| 属性 | 含义 | 默认 |
| --- | --- | --- |
| `callgraph.repo` | 被分析仓库根目录 | `.` |
| `callgraph.data` | 快照/WAL 持久化目录 | `./.callgraph-data` |

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/callgraph/build/full` | 全量构建并原子提交 |
| POST | `/api/callgraph/build/incremental` | 按文件哈希增量构建并提交 |
| POST | `/api/callgraph/cancel` | 请求取消最近一次构建（协作式） |
| GET  | `/api/callgraph/status` | 当前版本、方法数、边数、文件数 |
| GET  | `/api/callgraph/callers?owner&name&descriptor` | 全部调用方 |
| GET  | `/api/callgraph/callees?owner&name&descriptor` | 出边（含调用种类、行号、文件） |
| GET  | `/api/callgraph/reachable?owner&name&descriptor[&targetOwner&targetName]` | 可达集合或两点调用链 |
| GET  | `/api/callgraph/scc` | 递归方法集合与环路（SCC） |
| GET  | `/api/callgraph/cross-package` | 全部跨包调用边 |

`descriptor` 形如 `()`、`(int,java.lang.String)`，缺省为 `()`。

## 解析语义

### 方法身份与调用点

- 方法键：`ownerType + "#" + name + descriptor`，descriptor 记录**形参个数与可解析形参类型**；
  分派匹配使用「名字 + arity」，因此外部库形参类型缺失不影响参数数与候选计算。
- 解析器（JavaParser 17 语法）逐文件抽取类型（含嵌套类）、字段、方法/构造器、
  形参与局部变量声明类型、方法体内的调用点；匿名类内部调用不挂到外部方法
  （避免把匿名实现错归属，见「已知边界」）。
- 类型名按 JLS 简化顺序解析：本文件声明类型 → 显式 import → 同包 → `java.lang`（仅仓库内声明时）→ 通配 import。
  仓库内无源码的外部类型不建模（接收者推导不到则不产生边）。
- 无显式构造器的具体类合成默认 `<init>()`，使 `new X()` 正确成边。

### 接收者静态类型推导（过程内）

- 无接收者/`this`：接收者为属主类型；同类声明的 static 方法改写为静态绑定；
- 简单名：类型名 → 静态调用；否则查形参/局部变量，再查本类及祖先字段；
- 限定名 `a.b`：沿字段声明类型推进；`Type.x` 识别为类型限定静态字段；
- 链式调用 `getX().m()`：按方法返回类型逐步推进；
- 数组类型剥离维度后按元素类型处理。

### 虚调用分派（CHA，全部候选）

对接收者静态类型 `T`：

1. 求「仓库内可赋值给 `T` 的全部类型」`{C}`（`T` 自身 + 子类/实现类，沿类型层次闭包）；
2. 对每个 `C` 沿祖先链求 `name+arity` 的最近声明目标；
3. 去重汇总，得到该调用点在类型层次上可推导的**全部**候选目标。

这是 CHA 的合理上近似：不做过程间指针分析，候选集可能大于某次真实运行目标，
但不会漏掉类型层次允许的目标。描述符歧义（同名同 arity、形参类型不可区分）时
退化为祖先链全部候选，仍然保证不漏报。

## 增量一致性

全量与增量共用同一条管线：

```
解析表(逐文件 AST,用完即弃) -> 类型名规范化 -> 类型层次 -> 接收者推导 -> CHA 分派 -> 不可变 CallGraph
```

- 增量时只重新解析 ADDED/MODIFIED 文件，复用其余文件的解析产物，删除文件从表中移除；
- 分派始终基于**替换后的完整解析表**重算所有边：
  类型层次变化（`implements` 增删、父类变更）自动影响全部相关虚调用候选，
  无需额外的影响分析，也因此不可能残留过期边；
- 变更检测使用 SHA-256（非 mtime），结果随快照持久化，跨重启依然精确。

测试 `IncrementalConsistencyTest` 对「修改 / 删除 / 层次变化」三种场景断言
增量边集合与全量重建逐边相等。

## 并发与快照隔离

- 更新全部串行化于一把 `ReentrantLock`（单写者）；当前快照保存在 `volatile` 字段；
- 查询零加锁，拿到 `GraphSnapshot` 引用后访问的调用图/解析表均为不可变结构；
- 发布顺序：先 `store.commit`（原子落盘），再切换内存引用，读者不可能看到半更新状态；
- 旧快照对仍持有它的查询继续有效，直到引用消失后被 GC。

## 取消

`CancellationToken` 为协作式令牌，检查点位于：每个源文件解析前后、
`assemble` 的每个文件/每个调用点、BFS/Tarjan 的每批节点。
取消在「WAL 追加 / 快照发布」之前生效时抛出 `BuildCancelledException`，
已提交快照与持久化文件完全不被修改；WAL 已追加但未提交时由崩溃恢复重做。

## 崩溃恢复协议

```
.callgraph-data/
  wal.log                     追加型预写日志，每行一条 JSON（append+fsync）
  snapshots/
    snapshot-N.json.tmp       写入中的临时文件（崩溃残留 → 恢复时删除）
    snapshot-N.json           已提交快照（临时文件 fsync 后原子 rename；只保留最新）
```

1. 变更开始先写 WAL 并 fsync；
2. 快照写 `.tmp` → fsync → 同文件系统原子 rename → 删除旧快照 → 清空 WAL；
3. 重启：清理 `.tmp` → 读最新完整快照（损坏文件忽略/删除）→ 顺序重做
   `transactionId` 大于快照的 WAL 条目。

因此：commit 前崩溃 → 旧快照完好、WAL 待重做；rename 前崩溃 → 仅残留 `.tmp`；
rename 后、清 WAL 前崩溃 → 按事务号跳过，不重复应用。

## 性能边界

- **内存上界**：解析逐文件进行，任一时刻只有一个 JavaParser AST 驻留；AST 在该文件抽取后立即可回收。
  长期驻留的是精简模型（类型/方法/局部变量类型/调用点）与不可变邻接表，规模为
  `O(类型数 + 方法数 + 调用点数 + 边数)`，不随源文件大小之外的因素增长。
- 增量构建时间 ≈ `O(变更文件解析 + 全量分派重算)`；分派重算对每个调用点做一次
  子类型闭包枚举，最坏 `O(调用点数 × 类型数)`（CHA 固有成本），典型仓库远低于此。
- SCC 使用**迭代式 Tarjan**（显式帧栈），不占用 JVM 调用栈，可在数十万节点上运行；
  可达性为 BFS `O(V+E)`。
- 解析失败的文件记录指纹但不贡献边，避免增量反复重试。

## 已知边界（刻意的工程取舍）

- 不做过程间指针分析，CHA 候选是类型层次上近似（可能多报，不会漏报层次允许的目标）；
- 重载消歧使用 name+arity（形参为不可解析外部类型时可能合并候选，取保守上近似）；
- 匿名类/lambda 体内调用不建模为独立属主（不产生会错挂到外部方法的边）；
- 方法引用（`X::m`）、泛型类型参数的精确实例化不参与分派；
- record 隐式规范构造器、注解成员方法不合成调用目标；
- 外部库（无源码）类型上的调用不产生边，仅仓库内源码参与图构建。
