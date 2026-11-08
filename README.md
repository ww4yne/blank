# BLANK

面向墨水屏的原生 Android Apple Music 客户端，采用黑白界面、静态灰度封面、
大播放按钮与分页浏览。基于 Apple 官方 MusicKit Android SDK，不使用网页播放器，
并非 Apple 官方应用。

**应用 ID：`io.github.ww4yne.blank` · Android 11 及以上 · ARM64 / ARMv7。**

BLANK 使用全新的应用 ID，可与旧版墨听和官方 Apple Music 共存。
不会迁移旧应用的账号凭据；首次安装需要重新配置和授权。

## 功能

- 推荐分类、个人资料库、歌单与专辑曲目、曲库搜索。
- 完整列表播放、追加队列、进度定位、随机与循环模式。
- 当前播放页的“曲目操作”：添加收藏、减少类似推荐、添加到资料库。
  收藏使用官方 favorites 接口，不将“加入资料库”伪装成收藏。
  减少推荐提交歌曲负面评分，不立即跳过歌曲或修改当前队列。
- 原生前台播放服务、系统通知、锁屏控制与可缩放桌面播放器组件。
- 无转场、波纹或跑马灯；可见播放页每 15 秒更新进度，后台与暂停时不刷新进度。
- “播放 / 推荐 / 资料库”使用相同位置与尺寸的底部播放控制栏；播放页封面放大居中。
- Android Keystore 加密保存本机授权，禁用备份，配置页禁止截屏。
- 无音频进度时提供 45 秒超时提示；失败后可以确认重新载入播放内容。
  新播放请求不会复用已经失败的播放器；恢复播放重新检查本机凭据。
  播放诊断仅记录状态与数字错误码，不记录 Token、账号、歌曲或音频链接。

**限制：**不含电台、离线下载、逐字歌词或个人上传音频，不承诺无损或独占 DAC。
MusicKit 播放库为 1.1.1；目标设备上的间歇缓冲及播放请求故障尚未确认根因，
不能将 API 账号检查成功或模拟器检查通过等同于实机连续音频播放已经可靠。
重新载入从上次选择的歌曲或列表开始，不保留进度与之后追加的队列。
收藏和添加资料库返回“请求已接受”，实际同步及可用性由 Apple 决定。

## 安装与授权

从 [Releases](https://github.com/ww4yne/blank/releases) 下载 `BLANK-1.0.0.apk`。
这是本地调试签名的实验版本，不是应用商店正式发行版。

**公开安装包不包含 Developer Token、Music User Token 或私钥。**
需要自己的 Apple Developer MusicKit 接入凭证，以及有效的 Apple Music 订阅。
请保留已安装、已登录的官方 Apple Music App，以完成官方用户授权。

1. 在 Apple Developer 后台配置 MusicKit Media ID 与私钥。
2. 将 `.p8` 保存在项目之外的安全、非 OneDrive 目录，不上传 GitHub、不导入应用。
3. 使用 PowerShell 7 在本机生成 Developer Token：

   ```powershell
   .\New-DeveloperToken.ps1 -TeamId YOUR_TEAM_ID -KeyId YOUR_KEY_ID `
       -PrivateKeyPath 'C:\SecureKeys\AuthKey_YOUR_KEY_ID.p8'
   ```

4. 在“设置 → 更多设置 → 开发者配置（高级）”粘贴生成的 JWT，
   再到“Apple Music 账号”进行官方授权。不向 BLANK 输入 Apple 密码。
5. 默认 Token 有效期 30 天；到期后本机重新签发并更新。应用没有私钥，不能自动续期。

“检查账号连接”访问 Apple 个人商店接口，**不会刷新 Developer Token**。
若音频加载超时，点播放键确认重新载入，或查看“设置 → 播放与后台 → 播放诊断”。
API 可访问但音频不播放时，还需检查设备时间、网络、订阅及设备后台限制。

## 构建

所有源码、SDK、依赖、缓存和构建产物必须位于非 OneDrive 目录。

- Windows、PowerShell 7、JDK 21。
- Android SDK，安装 Platform 35；可通过 `JAVA_HOME`、`ANDROID_HOME` 指定工具位置。
- 从 Apple 官方下载 `AndroidMusicKitSDK1.1.2.zip`，放在仓库的**父目录**。
  SDK 压缩包、AAR 与签名密钥不纳入本仓库，使用者自行遵守 Apple SDK 条款。

```powershell
.\build.ps1
```

脚本提取官方 AAR、下载并核对 Gradle 8.11.1 SHA-256，运行
`assembleDebug testDebugUnitTest lintDebug`。缓存放在仓库 `.tools`，
最终通用 APK 位于 `dist\BLANK-1.0.0.apk`，不自动复制到 OneDrive。

个人自用、预配置凭证的构建：

```powershell
.\build.ps1 -DeveloperTokenPath "$env:LOCALAPPDATA\BLANK\developer-token.jwt"
```

生成 `dist\BLANK-1.0.0-personal.apk`。其中的 Developer Token 可被提取，
**不要公开上传或分发个人安装包**。不传此参数会清除上次生成的接入凭证。
任何构建都不读取或打包 Music User Token 或 `.p8`。

x86 模拟器可用 `-UiOnly` 构建独立的 `.uitest` 应用；该版本排除原生音频库，
**不能播放，不得作为播放器发布**。设备回归运行：

```powershell
.\build.ps1 -DeveloperTokenPath "$env:LOCALAPPDATA\BLANK\developer-token.jwt" `
    -Tasks @('connectedDebugAndroidTest')
```

集成检查中的歌曲、授权和网络响应为测试数据，不代表真实账号播放验证。
普通播放内核检查需要 ARM 设备，x86 UI 测试不能验证原生音频输出。

## 官方参考

- [MusicKit Android](https://developer.apple.com/musickit/android/)
- [Apple Music API](https://developer.apple.com/documentation/applemusicapi)
- [添加收藏](https://developer.apple.com/documentation/applemusicapi/add-resource-to-favorites)
- [歌曲评分](https://developer.apple.com/documentation/applemusicapi/add-a-personal-content-rating-33dop)
