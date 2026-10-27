# Eagle 图库浏览器（Android）

GitHub 自动 CI 已按要求关闭；提交前在本机构建并运行单元测试。
[![Release](https://img.shields.io/github/v/release/kkkevvvinnn/eagle_android)](https://github.com/kkkevvvinnn/eagle_android/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

在安卓设备上本地浏览由 [Eagle](https://eagle.cool/) 素材管理软件导出的 `.library` 图库。将电脑上的图库目录通过任意同步工具（Syncthing、Resilio Sync、网盘同步等）原样同步到手机后，用本应用选择该目录即可离线浏览。**所有数据均在本地处理，应用对图库目录只读，绝不修改其中任何文件。**

## 功能

- **本地索引**：基于图库根目录 `mtime.json` 的增量扫描，只重读变化的条目元数据，避免每次启动全量遍历；回收站（`isDeleted`）条目自动跳过；图片文件未同步完整的条目自动跳过并在补全后自愈；索引绑定完整图库URI（含提供方），目录移动、更换同步路径或提供方后重新选择目录会重建索引
- **多图库**：可添加多个 `.library` 目录，在设置页一键切换；扫描全部完成后才发布新库；扫描中或中断后隐藏缓存图片，重新扫描完成即可恢复
- **筛选**：标签多选（新默认“或”，按命中标签数量排序，可切换“与”）、未标记图片、评分下限、文件名搜索（支持字面 `%`/`_`）、六种排序；筛选状态自动记忆，已有保存的与/或语义保持
- **标签目录**：按原库分组／未分组展示，搜索标签并组合筛选；标签按最近30天自制Mac新增关联次数排序，同频用本机可浏览图片覆盖数兜底；同时显示当前完整筛选结果／全库已索引非回收站数量，零结果也能调整条件。根分组和Mac历史在重新扫描时刷新，辅助文件暂缺或损坏仍可看图
- **按主色找图**：11常用色、6位HEX自选与容差调整；与标签/评分/搜索相交，保留当前排序的全部匹配。匹配色块占比合计至少15%，不再限制最近150张；详情色卡可直接设为自选主色。旧RGB条件迁移为同色目标的默认容差主色条件，结果语义随之改变。缺少有效色板时会在本机分析当前范围，可查看进度或随时清除主色；缓存放在应用私有目录，不修改素材库。暂时无法分析的图片清除主色后仍可浏览；实际手机解码与性能尚未验证
- **瀑布流网格**：按元数据宽高比占位、Eagle 缩略图直读（缺失自动降级原图）、主色板底色、双指捏合在 2–4 列间切换（带连续缩放预览）、下拉刷新重扫
- **大图查看**：左右滑动切换、双指缩放（质心跟手）、平移（按图片实际显示区域约束边界）、双击以点击位置为中心放大；信息面板展示尺寸、大小、添加时间、星级、标签与主色板
- **多选分享**：长按进入多选，通过系统分享面板批量分享原图
- **主题**：深色 / 浅色 / 跟随系统，Material 3 边到边设计

## 安装

- 从 [GitHub Releases](https://github.com/kkkevvvinnn/eagle_android/releases) 下载最新 `app-release.apk` 直接安装，或
- 自行构建（见下文「构建」），`./gradlew :app:installDebug` 安装到已连接设备

要求 Android 8.0（API 26）及以上。

注意：自行构建的 debug 包与 Releases 的正式签名 release 包**签名不同，不能互相覆盖安装**，切换渠道需先卸载（会清除本机索引与设置，重新选择目录即可自动重建）。

## 使用

1. 用同步工具把电脑上的 `xxx.library` 目录**原样**同步到手机存储（目录结构必须保持一致）
2. 打开应用，选择该 `.library` 根目录并授权读取
3. 等待首次索引完成（万级图库约需一两分钟，之后均为增量扫描）
4. 在设置页可添加多个图库、切换主题、查看版本与版权信息

**1.11.1升级提示**：旧版本缓存没有完整的索引归属凭据，首次升级后需点击“重新扫描”。扫描中、扫描中断或索引归属不一致时图片暂时隐藏；可以对同一图库重新扫描恢复，不必删除原素材。此机制已通过离线构建和JVM故障注入，实际手机的SAF、Room/DataStore及进程生命周期仍待验证。

## 数据格式说明

Eagle `.library` 目录结构（本应用只读访问）：

```
xxx.library/
├── mtime.json              # { "<图片ID>": lastModified, "all": 总数 }，用于增量扫描
├── tags.json               # historyTags / starredTags 历史（非标签字典，未使用）
├── metadata.json           # 只读 tagsGroups 分组（Eagle4.0.0）
├── kevin-tag-history.json   # 可选，自制Mac新增标签历史（只读近30天排序）
└── images/
    └── <图片ID>.info/
        ├── metadata.json   # id/name/ext/width/height/star/tags/palettes/...
        │                   # tags 为名称字符串数组；star 可选 0-5（未评分时不写出）
        ├── <name>.<ext>    # 原图
        └── <name>_thumbnail.png  # 缩略图（网格直接使用）
```

标签数字基于本机已同步、原图可读的非回收站索引；缺失原图或未同步的素材不会计入。原版Eagle旧标签没有新增时间时按覆盖数排序。分组只影响展示，不改变全局与/或逻辑；Mac历史来源标记不是库身份验证。

较新版本的 Eagle 还会在库根目录生成 `backup/`、`vector-db/`、`actions.json`、`saved-filters.json` 等内容，本应用一律忽略，同步时包含或排除均不影响使用。

## 技术栈

- Kotlin + Jetpack Compose（Material 3），minSdk 26 / targetSdk 35
- Room（本地索引）+ Paging 3（占位符模式分页）、Coil（图片加载与缓存）、DataStore（设置持久化）、kotlinx.serialization
- SAF（Storage Access Framework）选目录并持久授权，无需申请存储权限
- 无 DI 框架（手工 `AppContainer`），无网络权限

## 构建

```bash
./gradlew :app:assembleDebug      # 构建 debug APK
./gradlew :app:testDebugUnitTest  # 运行单元测试（纯 JVM，无需设备）
./gradlew :app:installDebug       # 构建并安装到已连接设备
```

需要 JDK 17 与 Android SDK（`local.properties` 中配置 `sdk.dir`）。依赖版本集中管理于 `gradle/libs.versions.toml`。

## 持续集成与发版

- GitHub Android CI 已禁用，push/PR不再自动运行；不要自行启用或手动触发。提交前保留本地debug构建和单元测试。
- 推送形如 `v1.7.0` 的 tag 会自动构建**正式签名的 release APK** 并发布到 [GitHub Releases](https://github.com/kkkevvvinnn/eagle_android/releases)：

```bash
git tag v1.7.0 && git push origin v1.7.0
```

Release 签名密钥通过仓库 Secrets 注入（`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`），本地 `release.keystore` 与密码文件已列入 `.gitignore`，请务必离线备份且不要提交入库。

## 贡献与 AI 协作

欢迎 issue 与 PR。仓库根目录的 [AGENTS.md](AGENTS.md) 是面向 AI 编码助手（及想深入了解实现的人）的完整技术文档：架构、数据流、Eagle 格式细节、核心机制与不可破坏的关键不变量，改代码前建议先读它。

## 开源协议

[MIT License](LICENSE) © 2026 kkkevvvinnn

本项目与 Eagle 官方无关，仅解析其公开的导出目录格式。应用图标为原创绘制，与 Eagle 商标无关。
