# Eagle 图库浏览器（Android）

[![Android CI](https://github.com/kkkevvvinnn/eagle_android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/kkkevvvinnn/eagle_android/actions/workflows/android-ci.yml)

在安卓设备上本地浏览由 [Eagle](https://eagle.cool/) 素材管理软件导出的 `.library` 图库。将电脑上的图库目录通过任意同步工具（如 Syncthing、Resilio Sync、网盘同步等）原样同步到手机后，用本应用选择该目录即可离线浏览，所有数据均在本地处理。

## 功能

- **本地索引**：基于图库根目录 `mtime.json` 的增量扫描，只重读变化的条目元数据，避免每次启动全量遍历；回收站（`isDeleted`）条目自动跳过
- **多图库**：可添加多个 `.library` 目录，在设置页一键切换
- **筛选**：标签多选（与/或）、未标记图片、评分下限、文件名搜索，六种排序；筛选状态自动记忆；大图信息面板点色卡可按主色找相似图
- **瀑布流网格**：按元数据宽高比占位，直接使用 Eagle 生成的缩略图（缺失时自动降级原图），主色板底色，双指捏合在 2–4 列间切换
- **大图查看**：左右滑动切换、双指缩放、平移（带边界约束）、双击以点击位置为中心放大；信息面板展示尺寸、大小、添加时间、星级、标签与主色板
- **多选分享**：长按进入多选，通过系统分享面板批量分享原图
- **深色现代化 UI**：Material 3 深色主题，边到边显示

## 数据格式说明

Eagle `.library` 目录结构（本应用只读访问，绝不修改）：

```
xxx.library/
├── mtime.json              # { "<图片ID>": lastModified, "all": 总数 }，用于增量扫描
├── tags.json               # historyTags / starredTags 历史（非标签字典）
├── metadata.json           # 全局 folders / tagsGroups 等
└── images/
    └── <图片ID>.info/
        ├── metadata.json   # id/name/ext/width/height/star/tags/... （tags 为名称字符串数组，star 可选 0-5）
        ├── <name>.<ext>    # 原图
        └── <name>_thumbnail.png  # 缩略图（网格直接使用）
```

## 技术栈

- Kotlin + Jetpack Compose（Material 3），minSdk 26 / targetSdk 35
- Room（索引）+ Paging 3、Coil（图片加载与缓存）、DataStore（持久化）、kotlinx.serialization
- SAF（Storage Access Framework）选目录并持久授权，无需申请存储权限

## 构建

```bash
./gradlew :app:assembleDebug      # 构建 debug APK
./gradlew :app:testDebugUnitTest  # 运行单元测试
./gradlew :app:installDebug       # 构建并安装到已连接设备
```

需要 JDK 17 与 Android SDK（`local.properties` 中配置 `sdk.dir`）。

## 持续集成与发版

- 推送到 `main` 或发起 PR 时，GitHub Actions 自动构建 debug APK 并运行单元测试
- 推送形如 `v1.6.0` 的 tag 会自动构建**正式签名的 release APK** 并发布到 [GitHub Releases](https://github.com/kkkevvvinnn/eagle_android/releases)：

```bash
git tag v1.6.0 && git push origin v1.6.0
```

Release 签名密钥通过仓库 Secrets 注入（`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），本地 `release.keystore` 与密码文件已列入 `.gitignore`，请务必离线备份且不要提交入库。

## 开源协议

[MIT License](LICENSE) © 2026 kkkevvvinnn

本项目与 Eagle 官方无关，仅解析其公开的导出目录格式。
