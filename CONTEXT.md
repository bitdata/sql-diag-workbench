# CONTEXT — SQL Diagnostic Workbench 领域上下文

> 领域无处不在的语言（Ubiquitous Language）与关键术语。grilling 过程中增量维护。

## 核心术语

### Fragment（片段）
一条 SQL 文本的最小可执行/可分析单元。

- **决策 ADR-0001（+0005 修正）**：Fragment 是**值对象**。身份 = **规范化** SQL 文本 + dialect。规范化文本相同即同一 fragment，与产生路径无关。规范化器（P0+P1）消除换序/谓词方向/德摩根等已识别等价。

### Decomposition（拆解）
对一个 fragment 应用 rule，剥离某个结构（JOIN / WHERE / CTE / 谓词…），产生更简单的子 fragment。

- 拆解是**纯函数闭包**：给定原始 SQL + 规则集，闭包唯一确定（ADR-0001 的基础）。
- 拆解边方向：更复杂 → 更简单（"剥离了 X"）。

### Rule（拆解规则）
`DecompositionRule.apply(fragment) -> List<childFragment]`，纯函数。相同输入必产生相同子节点集合。

### User Edit（用户编辑）
人在编辑器中改写一个 fragment 的 SQL 文本。

- ⚠️ **不是 rule**——不满足纯函数闭包。
- ⚠️ **是链，不是 DAG**（用户 2026-06-19 更正）：编辑及其 lineage 是**线性链**，每步至多一个前驱。
- ⚠️ **身份仍是 SQL 文本**（ADR-0001）：编辑产物的身份只看文本。
- **与拆解严格分离**：编辑链是独立聚合，不占用 `parentIds`。
- **归类（ADR-0002）**：编辑器操作 `(before, after)`，若 `after` ∈ 当前 DAG 已有 fragment → 归类为拆解/反向拆解（导航，编辑事件被吞）；否则 → 编辑（新建 edit-chain 链接）。由**后端**判定。全局开关控制是否自动归类。
- **edit-chain = 人类残余**：仅记录无法由拆解解释的 fragment。

### Reverse Decomposition（反向拆解）
沿拆解边 `parentIds` 逆行——"加回"被先前剥离的结构。

- 可达集 = 当前 DAG 中已存在的祖先节点。
- 与正向拆解同属"拆解家族"，与编辑严格分离。

### Decomposition（拆解）—— DAG
对一个 fragment 应用 rule，剥离某结构，产生更简单的子 fragment。

- **纯函数闭包**：给定原始 SQL + 规则集，闭包唯一（ADR-0001 的基础）。
- `decomposition-principles.md` 的 DAG 原则**只适用于拆解**，不适用于编辑。
- `parentIds` 表达拆解边。方向：更复杂 → 更简单。

### Lineage（血缘）= 操作树 + 差分图（双层）
**定义（ADR-0003，2026-06-19）**：lineage 不是单一拓扑，是两层叠加：

1. **操作树**：用户实际编辑操作形成的**树**（分叉试探 + dead-end 旁支）。回退=导航，不新建边。当前 fragment 到根路径唯一。
2. **差分图**：fragment 文本间的差异包含关系，**含未操作的虚拟边**（如 `D→B`，闭合诊断）。节点集 = 当前 fragment 操作祖先 ∪ 其差分邻居。

- 诊断价值（用户场景）：二分搜索式罪责归因——`ΔAC`、`ΔCD` ⊆ `ΔAB` ⇒ 问题锁定 `ΔCD`，`D→B` 虚拟边闭合"ΔCD 是 ΔAB 唯一未排除子段"。
- dead-end（如 B）作**虚化节点**保留，撑起虚拟边，不占操作时间线。
- LineagePanel = 操作树（实线）+ 差分图（虚线虚拟边）叠加，**非纯 git-log**。
- "最有利于认知的路径"原则：操作树路径唯一自动满足；展示尽可能少的状态。

## 模型分层

```
拆解（Decomposition）            编辑（Edit）
  DAG，纯函数闭包                 链（linear），人改
  parentIds 边                   edit-chain 边（独立聚合）
  §2.1 唯一性在此成立             无闭包、无唯一性
       │                              │
       └──── fragment (identity = SQL text) ────┘

编辑器（统一入口）→ 后端归类 → 拆解边 or 编辑链边
```

### Atomic Change Unit（原子变更单元）= AST 节点变更
**定义（ADR-0004）**：`ΔXY` = X 到 Y 的 AST 节点变更集合，每 op = `{ADD|DEL|MOD, nodeType, location, old?, new?}`。

- 包含判定：`ΔP ⊆ ΔQ` ⟺ ΔP 每 op ∈ ΔQ（按 nodeType+location 匹配）。
- 诊断场景基础：`ΔAC`、`ΔCD` ⊆ `ΔAB` ⇒ `ΔDB = ΔAB \ ΔAD`，`D→B` 虚拟边可算。
- **承重墙**：AST diff 同时服务于 ADR-0002（归类）、ADR-0003（虚拟边）、LineagePanel（diff 展示）。
- 依赖 Calcite parser + AST diff 算法（待实现）。

### Change Attribution（变更归因）= 序列化 AST + LCS + 规范化
**定义（ADR-0005）**：编辑器改动 `(before, after)` → 后端 parse → 规范化 → 固定遍历序列化 → LCS diff → `Δ` op 集。纯后端，编辑器零改造。

### Canonicalizer（规范化器）= 承重墙，范围 P0+P1
**定义（ADR-0005）**：把 SQL AST 规范化为唯一形态，消除已识别等价。

- **P0**（纯结构，零风险）：AND/OR 子节点字典序排序（解决换序）、常量折叠、括号/空白/大小写归一。
- **P1**（局部代数，每条保语义测试）：谓词方向归一（`b<20`↔`20>b`）、德摩根等。
- **不在范围**：P2 全局重写（分配律/子查询↔JOIN）、P3 schema 依赖等价（与离线定位冲突）。
- **优雅降级（单向安全）**：未识别等价 → spurious op → 仅漏结论（漏 D→B），不产生假包含（错结论）。唯一假包含入口 = 规范化器 bug，由保语义测试覆盖。
- **双重职责**：服务 Δ 计算（diff 前规范化）+ fragment 身份判定（规范化文本相同 = 同一 fragment = 导航）。

## ADR 索引
- [ADR-0001](docs/adr/0001-fragment-identity.md) — Fragment 身份 = **规范化** SQL 文本（值对象）；拆解=DAG / 编辑严格分离
- [ADR-0002](docs/adr/0002-edit-classification.md) — 编辑器操作归类：等价即拆解（导航），否则编辑；后端判定；全局开关
- [ADR-0003](docs/adr/0003-lineage-topology.md) — Lineage = 操作树 + 差分图双层叠加（含虚拟边 D→B）
- [ADR-0004](docs/adr/0004-atomic-change-unit.md) — 原子变更单元 = AST 节点变更；AST diff 是承重墙
- [ADR-0005](docs/adr/0005-change-attribution-and-canonicalization.md) — 变更归因 = 序列化 AST + LCS；规范化器 P0+P1；身份修正为规范化文本
