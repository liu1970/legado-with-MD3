# 自动 reapply 工作流

本目录文件说明。

## 文件清单

| 文件 | 用途 |
|---|---|
| `patches/offline-cache-bookshelf-v1.patch` | 本次"离线缓存 + 书架改造"的 git diff patch（约 30 个文件、160KB）。下次拉取上游时由 workflow 自动应用 |
| `replays/BookshelfLastAddedStore.kt` | 进程内 `AtomicLong` 存储，"上次加入的书架"。首次应用时新建到 `app/src/main/java/io/legado/app/help/config/BookshelfLastAddedStore.kt` |
| `scripts/replay-offline-cache.sh` | 应用 patch + 跑架构护栏 + 跑 Kotlin 编译（KSP 自动重生 131.json） |
| `workflows/replay-offline-cache.yml` | 每天 02:00 UTC（北京时间 10:00）+ 手抄 workflow_dispatch 自动 rebase + apply + build |

## 触发流程

```text
schedule cron ─┐
              ├─→  fetch upstream ─→ rebase origin/main ─→ apply patch ─→ build
workflow_run  ┘                                                        │
                                                                       ├─ 成功 → 开 PR
                                                                       └─ 失败 → 开"manual rebase needed" PR
```

## 工作流做了什么

1. **fetch upstream**：拉取 `HapeLee/legado-with-MD3`（fork 上游）
2. **rebase origin/main**：把本地 main 接到上游 HEAD 之后（若已是上游 main，no-op）
4. **apply patch**：`git apply --3way .github/patches/offline-cache-bookshelf-v1.patch`
   - 上下文变更时改简单模式失败，git 会自动 fall back 到 3way merge
   - hunk 真正冲突（代码改了）会失败 → workflow fail
5. **verify**：跑 `verifyConfigArchitecture` 和 `:app:compileAppDebugKotlin`
   - KSP 编译时会自动重生成 `app/schemas/.../131.json`（不要手工维护）
6. **开 PR**：
   - 成功 → 开 "🔁 replay: reapply offcache + 书架改造" PR
   - 失败 → 开 "❌ replay failed: manual rebase needed" PR

## 不会做的事

- 不会自动 merge 到 main（要人工审阅后合并）
- 不会推 commit 到 main
- 不会让 fork 上游 `HapeLee/legado-with-MD3` PR 触发这个 workflow（`if` 条件过滤了）

## 手动触发

```bash
gh workflow run replay-offline-cache.yml
```

或 Actions 页面 → Reapply offcache + 书架改造 → Run workflow。

## patch 升级

如果以后又有一批类似 patch 需要自动 reapply：

1. 新建 `.github/patches/<feature>-v1.patch`
2. 复制 `apply-offline-cache-replay.sh` 为 `apply-<feature>-replay.sh`，改 patch / replay 文件路径
3. 复制 `replay-offline-cache.yml` 为 `replay-<feature>.yml`，改 workflow_call

## 相关文档

- `docs/dev/offline-cache-bookshelf-rework.md` — 改动清单 + 拉上游对账流程 + 设计取舍
- `.github/workflows/verify.yml` — 每次 push 跑 verify（四道门）
- `.github/workflows/autoupdatefork.yml` — 已存在的 fork 更新器（参考用）