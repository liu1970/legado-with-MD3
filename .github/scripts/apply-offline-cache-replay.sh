#!/usr/bin/env bash
# 应用离线缓存 + 书架改造 replay
#
# 这个脚本不创建任何 PR、也不直接 commit/push。
# 它把仓库里的 .github/patches/offline-cache-bookshelf-v1.patch 应用到工作区，
# 然后把 .github/replays/BookshelfLastAddedStore.kt 拷贝到正确的目录。
#
# 上游 fetch + rebase 后，如果 patch 因上下文冲突 apply 不上，脚本非零退出，
# 由调用方（GitHub Actions）生成 "auto-rebase failed" PR 让用户手动解决。
#
# 注意：131.json 由 Room KSP 在 compileAppDebugKotlin 时自动重生，不要手工维护。

set -euo pipefail

REPO_ROOT="${1:-$GITHUB_WORKSPACE}"
PATCH_FILE="$REPO_ROOT/.github/patches/offline-cache-bookshelf-v1.patch"
REPLAY_FILE="$REPO_ROOT/.github/replays/BookshelfLastAddedStore.kt"
TARGET_FILE="$REPO_ROOT/app/src/main/java/io/legado/app/help/config/BookshelfLastAddedStore.kt"

if [[ ! -f "$PATCH_FILE" ]]; then
  echo "ERROR: patch file missing: $PATCH_FILE"
  exit 2
fi

cd "$REPO_ROOT"

# 1. 应用 patch（3way merge，先 dry-run）
if ! git apply --check --3way "$PATCH_FILE" 2>&1; then
  echo "WARN: --check failed, retrying with 3way merge"
  if ! git apply --3way "$PATCH_FILE" 2>&1; then
    echo "ERROR: applying patch failed (3way merge)"
    exit 1
  fi
fi
echo "OK: patch applied"

# 2. 把 BookshelfLastAddedStore.kt 放到正确位置（首次应用时新建）
mkdir -p "$(dirname "$TARGET_FILE")"
if [[ -f "$REPLAY_FILE" ]]; then
  cp "$REPLAY_FILE" "$TARGET_FILE"
  echo "OK: replay file copied -> $TARGET_FILE"
fi

# 4. 静默验证（架构护栏 + Kotlin 编译 + KSP）
echo "==> running verifyConfigArchitecture..."
./gradlew --no-daemon --no-configuration-cache verifyConfigArchitecture

echo "==> running :app:compileAppDebugKotlin (KSP will regenerate 131.json)..."
./gradlew --no-daemon --no-configuration-cache :app:compileAppDebugKotlin

echo "==> done."