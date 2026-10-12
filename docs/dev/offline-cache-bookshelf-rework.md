# 详情页离线缓存 + 书架/分组重构

> 本次改动基于上游 main `9413bafc5`（`HapeLee/legado-with-MD3`）。下次 fetch + rebase 时按本文档对照检查每个点。

## 1. 用户需求

1. 详情页加"离线缓存"按钮，放在"阅读"附近
2. 点击行为：已入架 → 直接缓存；未入架 → 自动加入"上一次加入的书架"（无记录则 `IdAll`）
3. **一本书只能在一个书架里**（分组 → 书架单元化）：Room schema 改 `books.groupId: Long`（single-id），多位掩码收敛到最低位
4. 默认书架（`IdAll`）允许隐藏（`show=false`），其他负数预设允许删除

## 2. 改动清单（按文件）

### 2.1 新增

| 路径 | 用途 |
|---|---|
| `app/src/main/java/io/legado/app/help/config/BookshelfLastAddedStore.kt` | 进程内 `AtomicLong` 记录"最近加入的书架"，重启回落 `IdAll` |
| `app/schemas/io.legado.app.data.AppDatabase/131.json` | Room 自动生成；**不要手改**，让 `kspAppDebugKotlin` 重生 |

### 2.2 修改

**Schema 迁移相关**：

| 路径 | 改动 |
|---|---|
| `data/entities/Book.kt` | `var group: Long = 0` → `var groupId: Long = 0` |
| `data/AppDatabase.kt` | `version = 130 → 131`；迁移注释 |
| `data/DatabaseMigrations.kt` | 新增 `migration_130_131`：整表重建 + 列重命名 + 位掩码收敛 SQL；**books_r131 CREATE 一定要带完整 DEFAULT 子句**否则 Room 运行时校验抛 `IllegalStateException: Migration didn't properly handle` |

**DAO 层**：

| 路径 | 改动 |
|---|---|
| `data/dao/BookDao.kt` | `books.\`group\`` → `books.\`groupId\``；位掩码 `(\`group\` & X) > 0` → `WHERE \`groupId\` = X`；`PRIVATE_GROUP_MASK/PUBLIC_GROUP_MASK` 位求和 → `PRIVATE_GROUP_IDS/PUBLIC_GROUP_IDS` 子查询；`privateFacts` 投影 `groupMask` → `groupId` |
| `data/dao/BookGroupDao.kt` | `getGroupNames(id)` 位运算 → 等值 `WHERE groupId = :id`；`getUnusedId` 由 power-of-2 改为 `MAX(groupId)+1`；新增 `maxGroupId: Long` 查询；删除 `isInRules`（位掩码工具，已不需要） |

**Repository / Domain**：

| 路径 | 改动 |
|---|---|
| `data/repository/BookDomainRepositoryImpl.kt` | `book.group` → `book.groupId` |
| `data/repository/BookshelfAutoGroupRepository.kt` | 位掩码求和 → `Set<Long>`；私密分组优先不覆盖 |
| `data/repository/RemoteBookRepository.kt` | `newBook.group = book.group` → `groupId = groupId` |
| `data/repository/AiToolRepository.kt` | `facts.groupMask` → `facts.groupId` |
| `domain/model/BookGroupAssignment.kt` | `val group: Long` → `val groupId: Long` |
| `domain/model/PrivateAccess.kt` | `isPrivateBook(privateGroupMask: Long, ...)` → `isPrivateBook(privateGroupIds: Set<Long>, ...)`；`PrivateBookFacts.groupMask` → `groupId` |
| `domain/usecase/BookMigration.kt` | `target.group = group` → `groupId = groupId`（两处） |
| `domain/usecase/ImportBookshelfUseCase.kt` | `book.group = groupId` → `book.groupId = groupId` |
| `domain/usecase/UpdateBooksGroupUseCase.kt` | `book.group` → `book.groupId` |

**UI / ViewModel**：

| 路径 | 改动 |
|---|---|
| `ui/book/info/BookInfoContract.kt` | 新增 `BookInfoIntent.OfflineCacheClick`；`BookInfoBookUi.group` → `groupId` |
| `ui/book/info/BookInfoViewModel.kt` | 注入 `CacheBookChaptersUseCase`；新增 `onOfflineCacheClick` 路径：已入架 → 直接缓存；未入架 → `addToDefaultBookshelfThenCache` → `BookshelfLastAddedStore.lastAddedGroupIdOrDefault()`；`applyBookOnShelf` 写 `BookshelfLastAddedStore.recordLastAdded(book.groupId)`；`updateGroup` 覆盖（不是位移）；`refreshMeta` 用 `book.groupId > 0L` 取代位运算 |
| `ui/book/info/BookInfoScreen.kt` | **新增第 5 个 action card**（`Icons.Default.CloudDownload`，标签 `R.string.offline_cache`），与"加入书架/目录/换源/阅读记录"同一 Row；缩 padding 容 5 等宽；导入 `androidx.compose.material.icons.filled.CloudDownload` |
| `ui/book/info/BookInfoSheets.kt` | 选组 Sheet 由位掩码 XOR 改为单选：`selectedGroupId == group.groupId` |
| `ui/book/cache/manage/BookCacheManageScreen.kt` | `book.group and selectedGroupId` → `book.groupId == selectedGroupId`；`IdNetNone` 用 `book.groupId == 0L` |
| `ui/book/cache/manage/BookCacheManageViewModel.kt` | `BookCacheManageBook.group` → `groupId` |
| `ui/book/manage/BookshelfManageScreen.kt` | 位掩码 → groupId 等值；`resolveSelectionGroupMask` → `resolveSelectionGroupId`；调用点 `groupPickerCurrentGroupId = book.groupId.coerceAtLeast(0L)` |
| `ui/book/manga/MangaBookSnapshot.kt` | `val group: Long` → `val groupId: Long` |
| `ui/book/group/GroupEditSheet.kt` | 新增 `canDeleteGroup()` 私有函数：`groupId > 0 \|\| == Long.MIN_VALUE \|\| (<0 && != IdAll)`；`GroupEditSheet` 与 `GroupManageSheet` 的 startAction 用 `canDeleteGroup(group)` |
| `ui/main/bookshelf/BookshelfViewModel.kt` | `PrivateMarkers.groupMask: Long` → `groupIds: Set<Long>`；`isPrivate` 用 `isPrivateBook(groupId, privateGroupIds)` |
| `ui/main/bookshelf/BookShelfItem.kt` | 字段 `group: Long` → `groupId: Long`；`isLocked`、`toUiItem`、`toLightBook` 同步 |
| `ui/main/bookshelf/BookshelfCoverPreloader.kt` | 位掩码求和 → `Set<Long>` |
| `ui/main/bookshelf/GroupManageSheet.kt` | `startAction` 用 `canDeleteGroup` 等价判定 |
| `ui/widget/components/privacy/PrivateReadGate.kt` | `facts.groupMask` → `facts.groupId`（两处） |
| `ui/widget/components/privacy/PrivateReadGateViewModel.kt` | `PrivateBookFacts(groupMask=...)` → `groupId=...` |
| `ui/widget/components/privacy/PrivateListMasking.kt` | `itemFacts.groupMask` → `itemFacts.groupId`（两处） |
| `help/book/BookExtensions.kt` | `applyTagGroupRules/applyTagGroupRulesForBook` 从位 OR 改为 `book.groupId = matchedGroupId`（首个命中）；`updateTo` `newBook.group = group` → `groupId = groupId`；`inside.equals("group")` 模板语法改为 `groupId` |
| `res/values/strings.xml` | 新增 `offline_cache_started`（`Queued %1$d chapters for offline cache`）、`no_chapter_to_cache`（`No chapters to cache`） |

## 3. 拉取上游后的快速对账流程

### 3.1 一行命令列出本次所有改动点

```bash
git status --porcelain
# 期望看到上面 §2.2 列表里的全部 M 文件 + 两个 ?? 新文件
```

### 3.2 容易踩坑的合并冲突点（高优先级）

按"上游最可能改到、且本次改动跨多文件"排：

1. **`BookInfoViewModel.kt`**：上游改动 `addToBookshelf` / `addToBookshelfForOfflineCache` 路径时容易冲掉 `BookshelfLastAddedStore.recordLastAdded` 调用。**合并时确认**：
   - 构造参数保留 `cacheBookChaptersUseCase: CacheBookChaptersUseCase`
   - `applyBookOnShelf` 内有 `BookshelfLastAddedStore.recordLastAdded(book.groupId)`
   - `OfflineCacheClick` Intent 在 `onIntent` 分发
   - `addToDefaultBookshelfThenCache` 方法存在

2. **`BookDao.kt` 与 `Book.kt`**：上游若加新列或动 `books` 表，**必然冲突**。手动 rebase 时：
   - 新列追加到 `books_r131` 的 CREATE（按 131.json 的 createSql 顺序）
   - INSERT 列也要同步加
   - `131.json` 不需要手改——`kspAppDebugKotlin` 会重生

3. **`BookGroupDao.kt`**：`getUnusedId` / `isInRules` / `getGroupNames` 改过；上游若改 `idsSum` 仍可保留 `maxGroupId`

4. **`DatabaseMigrations.kt`**：每次 `migration_130_131` 是固定的 `migrations` 数组里的最后一个 `Migration(130, 131)`。如果上游已经把 `130→131` 改过别的，**需要把本次改动并入上游的迁移版本**（例如合并成 130→132 一次跑完）

5. **`GroupEditSheet.kt` `canDeleteGroup`**：私有、纯函数；上游动到 `startAction` 过滤时要重新接入

### 3.3 三步快速验证

```bash
# 1) 架构护栏
.\gradlew.bat verifyConfigArchitecture

# 2) 编译
.\gradlew.bat :app:compileAppDebugKotlin

# 3) 装配 + 装到设备 / 启动验证 Room 130→131 迁移
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s RoomOpenHelper:V RoomMigration:V
```

### 3.4 启动期 Room 校验失败的 debug 路径

如果再次出现 `IllegalStateException: Migration didn't properly handle: books`：

1. `adb logcat -s RoomOpenHelper:V | grep "Expected:"` 拷出期望的 `TableInfo { columns = ... }`
2. 比对本地 `app/schemas/io.legado.app.data.AppDatabase/131.json` 中 `books` 实体的 `fields` 数组与 `migration_130_131` 的 `books_r131` CREATE TABLE
3. 常见差异点：
   - **缺 DEFAULT 子句** → 报错里 `defaultValue = 'undefined'`
   - **字段缺失** → `MissingColumn`；把 131.json 的字段一一加到 `books_r131`
   - **类型不一致** → `affinity = 'undefined'`；调 `INTEGER / TEXT` 类型
   - **索引差异** → 上游 `@Index` 改过；`migration_130_131` 末尾的 `CREATE INDEX` 也要更新
4. 修改后**删掉** `131.json`（让 KSP 自动重生正确的 `identityHash`），重跑 `compileAppDebugKotlin`

## 4. 关键设计取舍（避免下次误改）

| 决策 | 为什么 | 不要做的事 |
|---|---|---|
| `BookshelfLastAddedStore` 用进程内 `AtomicLong`，**不持久化** | 用户原话"第一本书默认加入全部"——重启即回落 IdAll，行为正确且不需要 SharedPreferences/Room 迁移 | 改成 SharedPreferences 持久化——反而引入跨设备同步、迁移冲突问题 |
| 切 `show=false` 实现"隐藏默认书架" | BookshelfViewModel 已用 `flowShow()` 过滤 `show=0`；无需新增列；`group.show=false` 立即生效 | 新增 `isHidden` 列——冗余 |
| `applyTagGroupRules` 改覆盖式 | groupId 是 single-id 不能 OR；语义上"一本书只能在一个书架"→ 自动规则也单一归属 | 改回位 OR——破坏单书架语义 |
| `BookshelfAutoGroupRepository.applyPlan` 私密优先不覆盖 | 私密分组是用户隐私标记，AI 自动归类不应覆盖 | 让 AI 改私密书——隐私风险 |
| `getUnusedId` 用 `MAX(groupId)+1` 而非 power-of-2 | single-id 不需要位掩码；连续 ID 可读 | 改回 power-of-2——浪费位空间，违背 single-id 语义 |
| Room migration 整表重建（非 `ALTER TABLE RENAME COLUMN`） | minSdk 26 没有 SQLite 3.35 的 RENAME COLUMN | 用 `@RenameColumn` 注解——Room 不支持 |

## 5. 已知未验证项（需真机/集成测试）

1. **Room 130→131 真实迁移已成功**（你在 logcat 里看到 App 进桌面），但**老数据收敛 SQL**（多位掩码 → 最低位）只在首次启动运行——已存在的多位书会被收敛到最低位。建议真机备份数据后手动 `adb shell run-as io.legato.kazusa.debug sqlite3 databases/legado.db "select bookUrl, group from books where (group & (group-1)) != 0 and group > 0;"` 看收敛结果
2. **`BookInfoScreen` 5 等宽 action cards 视觉**：padding 已缩到 `(horizontal=4.dp, vertical=10.dp)`、`verticalArrangement=spacedBy 2.dp`，但仍需要真机视觉确认文字不溢出
3. **进程内 `BookshelfLastAddedStore` 在 App 被系统 kill 后丢失**：重启后回落到 `IdAll`——按用户原话接受
4. **离线缓存按钮在 `privateLocked` 详情页**：当前 `BookInfoActions` 没有 `privateLocked` 保护（FAB 的 `if (!state.privateLocked)` 只针对阅读按钮）；落库 execute 会失败被 `onError` 兜底。如需也保护，可在 `BookInfoActions` 加 `if (state.privateLocked) return`
5. **删除负数预设后的下游影响**：`IdLocal/IdAudio/IdManga/IdText/IdError/IdReading/IdUnread/IdReadFinished*` 等筛选都是 `flowNetNoGroup/flowManga/flowText/flowUpdateError/flowReading/flowUnread/flowReadFinished/...` 等 BookDao 方法基于 `book.type / book.kind / isNotShelf` 字段查询，**与 book_groups 表内容无关**——删除预设行不会让 tab 误报
6. **手动隐藏 `IdAll`**：`flowShow` 不返回 IdAll，BookshelfViewModel.groupsFlow 不含它，BookshelfUiState.groups 拼接时少一项——确认书架头部 tab 不会出现"全部"以外的负面行为

## 6. 回归基线（防止下次误改）

`verifyConfigArchitecture` 的基线（位于 `build.gradle.kts` 的 `legacyPreferenceCallBaseline / legacyDaoInjectionBaseline / legacyUiDaoAccessBaseline`）当前值如下：

| 文件 | 类型 | 当前基线 |
|---|---|---|
| `ui/book/info/BookInfoViewModel.kt` | DAO 直连 | 0 |
| `ui/book/readaloud/cloudtts/CloudTtsViewModel.kt` | DAO 直连 | 13（历史债，冻结） |

**新增 DAO 直连** 会让护栏报红——本次改动 A/B/C/D/E 没有新增任何 DAO 直连，所有 DB 访问都走 Repository/UseCase/Gateway。