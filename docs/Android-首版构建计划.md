# LiveToFrame Android 首版构建计划

首验对象为用户手边的 OPPO，首版交付可装机的自用清理 APK。产品行为遵循 [跨平台产品与工程规范](跨平台产品与工程规范.md)。历史 OPPO 样本经互联导入 iPhone，原始封装需要从 OPPO 本机重新取证。

[双平台 HTML 原型，切换到 Android](LiveToFrame-Prototype.html)可体验相册选择、一键保存、副本预览、逐张核对及系统回收站确认。一键保存是本轮原型提案，以下装机记录对应既有版本。

功能名和两端首页标题均为「实况转静态」。按日／全部、筛选、照片网格和底部按钮依照[跨平台视觉与交互设计规范](跨平台视觉与交互设计规范.md)实现。页面返回、照片授权与回收站确认遵循 Android 系统交互。

## P0 · 原片与相册验证

- 工程位于 `android/`。包名 `app.livetoframe.personal`，最低 Android 11（API 30），编译与目标 API 36。使用 JDK 17、AGP 8.13.2、Gradle Wrapper 8.13、Kotlin 与 Compose 插件 2.3.21，Compose BOM 2026.02.00。新版 BOM 的 Compose 库要求 API 37 与 AGP 9.1，当前固定兼容 API 36 的版本。
- 登记 OPPO 机型、系统构建号、相机和相册版本。使用 10–20 张专门准备的原片，涵盖普通动态 JPEG、静态对照、HDR、已编辑和换封面；有 HEIC 或其他封装时也登记。USB 原文件或本机文件管理器导入，保留原路径和后缀信息。
- 诊断包从系统文件选择器读取本机存储文件。对标准 XMP Motion Photo 目录与旧版 MicroVideo 偏移验证 JPEG 和尾部 MP4 边界及可读视频轨道。基于 OPPO Find X8 Ultra 的两张原片，已加入 OLivePhoto v2 Ultra HDR JPEG 路径。交叉核对 JPEG、XMP 目录、MPF 增益图及 OPPO 主视频长度；保留增益图编码和位置，修正实样中不一致的主图长度字段，移除动态尾部。未核验的 HDR、扩展 XMP、额外图像和私有结构按原因跳过。此包只保存和查看副本。
- 静态部分按字节复制，清理动态 XMP，保留原有 JPEG 编码和其他段。MediaStore 以 `IS_PENDING` 写入 `Pictures/LiveToFrame/`，发布前后校验哈希，回读尺寸、解码和真实文件名。Android 14 及以上额外检查 HDR 副本的系统解码结果是否含增益图。记录源与副本字节、准备/写入/回读时间。相册是否显示静态照片、当前封面和关键元数据是否相符，须在 OPPO 相册逐项对照。
- 验证门槛是目标机上有实际支持的 OPPO 样本，且系统相册回读、封面、静态分类与关键元数据全部通过。无法完成证明时保持诊断能力。

## 当前样本证据

2026-11-16 收到两张 OPPO Find X8 Ultra 原片，均为 3072 × 4096 的 OLivePhoto v2 Ultra HDR JPEG。两张在 Mac 上均通过生产解析代码的提取核验。除动态 XMP 和 MPF 主图长度字段外，静态部分逐字节一致，包括 EXIF、ICC 和增益图；解码后主图像素一致，主视频成功识别为 HEVC。副本与原片的字节差分别为 9,258,078 B、8,963,319 B。

真机已验证 OPPO Find X8 Ultra 卫星通信版（PKU110），ColorOS 17.0.0.104(CN01)、相机 7.006.123、相册 17.10.41。诊断版 0.1-p0.1 的两张副本均通过 MediaStore 读回与 Android 增益图解码检查。ColorOS 相册可打开两张副本，显示 ProXDR，无「实况」控件；同一查看器中两张原件均显示「实况」及 ProXDR。两张原件的 SHA-256 前后相同。

ColorOS 文件选择器把本机照片作为媒体文档返回，普通读取会屏蔽 EXIF 中的部分位置字节。诊断版通过原始媒体授权读取，真机只授予这两张照片；最终回读文件的 EXIF（含拍摄位置）、ICC、MPF、JPEG 编码及增益图字节和 Mac 参考副本一致，XMP 的元素与属性一致。广泛格式覆盖及编辑/换封面样本待补。

## P1 · 选片、批量保存与恢复

- 以 MediaStore 读取本地图像，按 API 版本适配全量、部分与拒绝权限，前台刷新授权状态。保护收藏，无法确认的原件不提供移除资格。原始字节和位置元数据按系统授权取得，无法保证保真时明确跳过。
- 固定五列，提供按日、全部、只看已选、按日批选、按 ID 恢复或清空选择、一次长按预览、批次核对、真实进度与结果，选择区和移除区按各自实际批次估算。
- 记录操作 ID、原件 URI、卷版本、媒体资源更新序号、源文件 SHA-256、已写入的副本 URI 和核验状态。处理单路串行，恢复先对账，结果不明确时保留待核对。新增设备或格式必须补原片回归。内容分类和跨品牌适配在首版之后安排。

### 0.2-p1 使用与验证

- 当前相册索引读取本机内部存储里 `DCIM/Camera/` 的 JPEG，只显示授权范围内有动态标记的候选。候选扫描仅检查 JPEG 文件头。保存前再核对资产版本、收藏状态及原始字节，完整验证静态和视频边界。ColorOS 照片访问、部分相册和原始位置权限一起发起，用户可只授权需要操作的照片。
- 在既有 OPPO Find X8 Ultra 卫星通信版装机。仅授权原先两张原片，验证按日浏览、选/取消当天、切到「全部」保留勾选、结束进程后恢复选择、本次结果重开，以及无视口跳动。批量两张均通过真实 MediaStore 回读，分别约 677 和 578 ms。后缀 `(2)` 的两个 P1 副本与 P0.1 的已保真副本 SHA-256 完全相同。两张相机原件 SHA-256 前后一致。
- 开发包已包含保存日志和中断对账。真机本轮测试了完成后的结果恢复，尚未在写入期间故意终止进程，也未用全量或跨日期、多机型相册验收。

## P2 · 回收站与自用 APK

- 对原件和副本再次做版本、指纹、权限、收藏及相册归属核验。只为通过的原件提交 `MediaStore.createTrashRequest`。系统确认后查询回收站状态，取消或读不到时保留可核对状态。
- 使用 30–50 张可舍弃的真机样本验证选择、保存、异常恢复、取消系统确认、成功移入回收站和恢复。未经核验进入回收站的原件数须为 0。记录冷暖启动、滚动、分阶段耗时和文件字节差。
- 交付以稳定自用签名生成的 APK、SHA-256、机型支持表、安装与覆盖升级说明。升级须保持包名和签名；保留上一个 APK 与签名。设备不允许跨版本降级时，从已验证源码用相同签名构建更高版本号的回退包。原件恢复从系统回收站操作，单独核对副本状态。上架另行规划。

### 0.3-p2 使用与验证

- 保存后进入「核对可移入回收站的原件」，勾选并点「复核并继续」。只处理已有已发布静态副本、完整核验记录及副本身份的项。重新读取原件与副本，分别比对资产身份、卷版本、媒体更新序号、目录及 SHA-256；收藏、不可访问、变化或无法证明的项目保留。通过项以 `createTrashRequest` 交给系统确认。两张 P1 历史结果因缺少副本版本记录，升级后仍可查看副本，无法从该批次发起回收站请求。
- 在同一台 OPPO 使用两张独立合成的可舍弃 JPEG 动态样本 A、B。两张都通过保存和回读。保存后将 B 标为收藏，复核把 B 排除。系统取消后两张 `IS_TRASHED=0`。再次确认仅 A 进入回收站（`IS_TRASHED=1`），可在 ColorOS 相册「图集 → 最近删除」找到。恢复后 A 回到 `DCIM/Camera/`、同一 MediaStore ID、`IS_TRASHED=0`，SHA-256 与测试前一致，结果页确认已恢复。仅提交 B 时无系统确认弹窗。两张测试静态副本以及先前的两张真实 OPPO 原片均仍存在。
- 安装包、P1 回退包和 `SHA256SUMS` 位于 `android/app/build/outputs/livetoframe/`。两版沿用本机同一调试签名。降级受系统限制时，保留现有数据并用备份源码、同一签名及更高版本号构建回退包。
- 两张隔离样本的取消、移入和恢复已通过。不同机型的系统回收站和长期性能待测。

### 批量与中断补充验收（0.3-p2）

- 在同一台 OPPO 额外准备 42 张可舍弃合成动态 JPEG。其中 A 组 30 张一次选中、全部保存并回读核验。相册中 30 份副本的 SHA-256 与写入记录一致；读取、写入和回读阶段合计 2.965 秒。B 组 6 张在首次观察写入窗口时正常完成。
- 在 C 组的第 3 张（C04）实际写入 8,192 字节后，用调试器暂停写入线程并强制停止 App。C04 的副本行仍是 `IS_PENDING=1`。重启后显示 C05、C06 已核验，C04 待核验，且没有自动多生成副本。回收站清单和系统确认仅含两张已核验原件，随后取消系统确认。
- **续跑不重复验收未通过。** 重启仍恢复原来 6 张选择，再次点击保存会为 C05、C06 各创建一张 `(1)` 副本，与首次副本 SHA-256 相同。其余未处理的三张成功完成，C04 仍待核验，且不进入回收站核对清单。42 张样本原件均为 `IS_TRASHED=0`，与前两张真实 OPPO 原片共 44 个原文件的 SHA-256 前后一致。

### 中断续跑修复验收（0.3.2-p2，最终包 0.3.3-p2）

- 将批次照片、操作号及原片版本写入持久化批次清单。相同批次重启后沿用原操作号，已核验项返回 journal 中原副本 URI，未开始项继续处理。仅当中断目标明确属于本应用、位于 `Pictures/LiveToFrame/` 且仍为 `IS_PENDING=1` 时，才删除临时行并用相同操作号重做。缺少最终状态字段但有目标 URI 的记录也按中断处理；目标已发布或归属不能确认时保留「待核验」并阻止进入回收站。
- 在 OPPO 上对 R08 目标写入线程实际暂停于 8,192 字节并强制结束进程。重启后结果页为 R09 已核验、R08 待核验、R07 尚未开始；回收站复核清单只列 R09。续跑后复用 R09 原 MediaStore ID `1000089749`，清除 R08 中断目标 ID `1000089750` 并只生成一份通过回读的副本 ID `1000089751`，R07 生成一份副本 ID `1000089752`。再次重启结果仍为 3/3 核验通过，三个原件均 `IS_TRASHED=0`，每张恰有一份本轮副本。
- 中断记录单测、14 项 JVM 单测、`:app:assembleDebug` 和 `:app:lintDebug` 通过。随后补充确保已核验结果即使缺少回收站版本标记也只复用副本，不触发重写。最终 APK `LiveToFrame-0.3.3-p2-resume.apk` 已同签名覆盖安装并通过 v2 签名校验，SHA-256 `08988ac0305d3d77066b06d431800736063273e5515a7e8b08eac2e00a409d79`。前两张真实 OPPO 原片哈希仍为 `a239669f…cbc596` 与 `e222f889…c29f71`。

## 构建与装机

安装 SDK 平台 36、AGP 所需的 Build Tools 和 platform-tools。为命令行设置 JDK 17 与 `ANDROID_HOME`，在项目的 `android/` 目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
"$ANDROID_HOME/platform-tools/adb" devices -l
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

当前版本为 `0.3.5-p2`。打开 App 后授权照片，已显示的照片可在扫描期间选中和保存。结束进程后重开，相册勾选为空。已核验的副本可从结果页打开，也可在系统相册的 `Pictures/LiveToFrame` 查看。已提交的中断批次可从「本次结果 → 继续未完成批次」按原操作记录接着处理。前文真机记录对应各阶段旧版本。

## 复用边界

Media3 用于预览及标准解析对照。ZLC 有 OPPO 适配和已发布 APK，可独立比对；MotionCraft 的相关路径标明含源自 ZLC 的代码。静态提取依据公开格式与本机原片独立实现。各项目状态、代码及运行证据可从 [Media3](https://github.com/androidx/media)、[ZLC](https://github.com/zsz-of/ZLC/releases)、[MotionCraft](https://github.com/WeiErLiTeo/MotionCraft/blob/main/app/src/main/java/com/example/core/zlc/ZlcGooglePlugin.kt) 核对。Android 官方参考见 [Ultra HDR 格式](https://developer.android.com/media/platform/hdr-image-format)、[Motion Photo 格式](https://developer.android.com/media/platform/motion-photo-format)、[MediaStore](https://developer.android.com/training/data-storage/shared/media) 和 [部分照片授权](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)。
