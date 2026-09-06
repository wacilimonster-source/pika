#!/usr/bin/env bash
# pika 一键发版脚本 v1.5.46
# 用法: 在项目根目录执行 bash release.sh
# 前提: gbuild.sh 存在 + git remote origin 为 SSH 且已授权
set -euo pipefail

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"
VER=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
VC=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
APK_NAME="pika-v${VER}.apk"
APK_OUT="app/build/outputs/apk/release/app-release.apk"
SHA256_FIELD="sha256"

echo "=== pika 发版 v${VER} (versionCode ${VC}) ==="
echo ""

# 1. 构建 Release APK（绕 wrapper；release 构建未挂 lint，无需 -x 排除）
echo "[1/5] 构建 assembleRelease ..."
bash gbuild.sh assembleRelease
if [ ! -f "$APK_OUT" ]; then
  echo "!! 构建失败：找不到 $APK_OUT" >&2
  exit 1
fi
echo "    构建成功: $APK_OUT ($(du -h "$APK_OUT" | cut -f1))"

# 2. 计算 SHA-256
echo "[2/5] 计算 SHA-256 ..."
SHA=$(sha256sum "$APK_OUT" | cut -d' ' -f1)
echo "    SHA-256: $SHA"

# 3. 重命名 APK 到项目根目录
echo "[3/5] 重命名 APK → $APK_NAME ..."
cp "$APK_OUT" "$APK_NAME"
echo "    已复制到 $PROJECT_DIR/$APK_NAME ($(du -h "$APK_NAME" | cut -f1))"

# 4. 回填 update.json 的 sha256 字段
# 注意：本机 python3 是坏存根(exit 49)，必须用 python；
# 失败时直接中止，禁止用 sed 兜底（会插入重复键写坏 JSON）
echo "[4/5] 更新 update.json ..."
python -c "
import json
with open('update.json','r',encoding='utf-8') as f: d=json.load(f)
d['$SHA256_FIELD']='$SHA'
with open('update.json','w',encoding='utf-8') as f: json.dump(d,f,ensure_ascii=False,indent=2)
print('    sha256 已更新')
"

# 5. Git commit & push
echo "[5/5] Git commit & push ..."
git add "app/build.gradle.kts" "update.json" "$APK_NAME"
git commit -m "release: v${VER} (build ${VC})

- 设置页分组重构：数据源/阅读/通用三组卡片布局
- 新增数据源管理二级页（账号登录状态 + 禁漫API域名/恢复默认）
- 新增默认阅读模式设置，与阅读器内切换同步
- 关于改为对话框（版本/简介/来源声明）
- 我的页：收藏改名收藏的作品，移除我的评论入口"

git push origin main
echo ""
echo "=== 发版完成！==="
echo "  APK: https://raw.githubusercontent.com/wacilimonster-source/pika/main/$APK_NAME"
echo "  SHA: $SHA"
echo ""
echo "下载 update.json 可核对: https://raw.githubusercontent.com/wacilimonster-source/pika/main/update.json"
