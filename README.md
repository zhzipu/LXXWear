# LX-N-XW-Music

独立的手表端工程，自带 Gradle 构建，不依赖、也不进入手机端仓库。

它是 `D:\pycode\lx-netease-music-mobile-main` 的**分支产物**：功能方向跟随手机端，但代码与构建完全独立，
在 `D:\pycode\LX-N-XW-Music` 单独维护。

从 WearMusic（第三方网易云音乐手表客户端）中剥离出的最小可运行框架，**只保留界面、播放器、播放列表、歌词**四块，
不再包含任何账号、接口、下载、导入等业务功能。

## 保留的内容

| 模块 | 文件 |
| --- | --- |
| 界面 | `ui/MainActivity`（横向四页 ViewPager2）、`ui/PlayListActivity`、`adapter/ViewPagerAdapter`、`adapter/MusicAdapter` |
| 播放器 | `service/MusicPlayerService`（MediaPlayer + Binder + MediaSession）、`fragment/PlayerFragment`（封面 / 歌名 / 歌手 / 上下曲） |
| 播放列表 | `fragment/QueueFragment`（当前队列页）、`player/PlaylistProvider`、`model/Song`、`res/layout/activity_playlist.xml`、`res/layout/item_song.xml` |
| 歌单 | `fragment/SongListFragment`（手机同步下来的歌单，两层：歌单 → 歌） |
| 歌词 | `fragment/LyricsFragment`（LrcView，支持拖动跳转） |
| 局域网互联 | `lan/*`（发现 / HTTP 客户端 / 整单同步 / 本地曲库）、`ui/LanActivity` |

页面布局：横向四列 **歌单 → 播放器 → 播放列表 → 控制台**，播放器在中间：
往右滑是歌单（手机同步下来的分组），往左滑是播放列表（播放器当前这一队列，点一首立刻切歌并回到播放器），
再往左是控制台（音量、播放模式、播放列表、局域网同步入口）。
播放器列本身是上下两页：**上滑进歌词、下滑回播放器**（`widget/VerticalPagerLayout` + `fragment/PlayerPagerFragment`），
歌词页整页是 LrcView，竖向拖动用来跳转歌词，所以下滑返回关掉，回播放器页点顶部时间栏。

## 播放列表数据

应用启动时从 `app/src/main/assets/playlist.json` 读取，格式：

```json
[
  {
    "name": "歌名",
    "artist": "歌手",
    "cover": "封面图片地址",
    "url": "音频地址（http(s) 或本地路径）",
    "lyric": "[00:00.00]第一句歌词\n[00:03.00]第二句歌词"
  }
]
```

`url` 为空的条目会被忽略；`cover`、`lyric` 可以为空，界面会显示占位图和「暂无歌词」。

也可以完全不用 assets，直接注入自己的数据源：

```java
PlaylistProvider.setSongs(mySongs); // 在播放器页创建之前调用
```

## 构建

本工程独立构建，不参与手机端的构建流程：

```bash
cd D:/pycode/LX-N-XW-Music
gradle assembleDebug            # 已验证 Gradle 8.10.2 + JDK 17
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `applicationId` 仍是 `cn.wearbbs.music`，可以和手机端 App 同时装在设备上。
- 依赖 `me.wcy:lrcview:2.2` 只存在于已停服的 jcenter，根 `build.gradle` 里已用阿里云 jcenter 镜像替换。

## 局域网互联（自动对齐手机端选中的歌单）

手表和手机连同一个 Wi-Fi 就会**自动同步**，不需要点任何按钮：

- 触发时机：应用启动后约 2 秒、系统网络状态变化、每 10 分钟一次
- 判定依据：手机端 `/api/playlist` 里的 `revision`（歌单内容指纹）+ 本地曲目数量
- 版本没变 → 什么都不做；变了 → 只补缺的音频、删掉手机端已移除的歌、顺序跟随手机
- 同步不打断播放：正在播的那首若仍在新歌单里会按 `id` 保留位置，且不会自己发声，
  按播放/切歌才生效
- 找不到手机、超时都静默跳过，不弹窗

歌单同步来源是手机端**当前选中的歌单**（`playlistId` / `playlistName` 由手机端给）。

**配对**：同步页 →「配对手机」，手表显示二维码（内含手表 IP、配对端口、6 位配对码），手机扫码后
调用手表的 `/pair` 换取 token，双方绑定。配对后手表只接受带同一 token 的广播与请求，
换手机在配对页「解除配对」即可。不配对也能用，但同网段多台设备时容易同步到别人的手机。

手机端（`lx-netease-music-mobile`）侧边栏「设置」上方的 **LXXWear** 页负责配对：点「打开相机扫码」
拍下手表屏幕上的二维码即可自动填好地址与配对码并完成配对（识别用 MLKit 离线模型，扫不出来时
页面上也有手动填 IP / 端口 / 配对码的入口）。

手动入口在**控制台页第三行的同步图标**：可以看到歌单名、本地曲目数与占用空间，也能强制重新检查、
查看具体进度和错误。同步过程中手机上没有某首歌的文件时，由手机端先自行下载（`prepare` 轮询）再发出来。

本地曲库在 `Android/data/cn.wearbbs.music/files/lan_library/`，`index.json` 记录歌单信息与版本；
应用启动时优先加载它，没有同步过才回退到 `assets/playlist.json`。

完整接口、时序与自动同步策略见 **[docs/LAN_PROTOCOL.md](docs/LAN_PROTOCOL.md)**。

### 没有手机端时怎么自测

电脑上跑自带的最小服务端（只依赖 Python 标准库）：

```bash
# 把音频放进 tools/mock/media，文件名建议「歌手 - 歌名.mp3」，同名 .lrc / .jpg 会被一起发出去
python tools/mock_server.py --media tools/mock/media
```

它会在 `8686` 起 HTTP 服务、`8687` 每 2 秒发 UDP 广播；手表进同步页即可整单拉取。
记得放行防火墙的 TCP 8686 与 UDP 8687，且电脑和手表在同一网段。

手机端（`lx-netease-music-mobile`）目前还没有这套 HTTP 服务，需要按协议文档补上；
`docs/LAN_PROTOCOL.md` 第 5 节写了 RN 侧的落地方案（静态文件服务 + `react-native-udp` 广播 + 缓存目录）。

## 关键约定

- `PlaylistProvider` 持有播放列表和当前索引，是唯一的数据来源。
- `MusicPlayerService` 只做 MediaPlayer 的播放控制，播放列表通过 `startService` 的 `urls` / `index` 传入。
- 页面之间用 `LocalBroadcastManager` + `PlayerActions` 通信：进度、选歌、切歌。
- `PlayerFragment.binder` 是拿播放器控制权的入口，未连接时为 `null`。

## 已删除的功能

登录 / 扫码 / 账号信息、搜索与热搜、我的歌单、歌单详情、音乐云盘、私人 FM、本地音乐与 FTP 导入、
评论与回复、MV 播放、下载、分享二维码、收藏、设置、关于、开源声明、更新检查、意见反馈、AppCenter 统计、
以及 `cn.jackuxl` 下的整套网易云接口与数据模型。

## 许可证

沿用原项目 AGPL-3.0，详见 [LICENSE](LICENSE)。
