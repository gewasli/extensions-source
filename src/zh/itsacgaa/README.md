# ItSACG — Tachiyomi / Mihon / Keiyoushi 扩展（漫画中心）

为 `https://www.itsacgaa.online`（Discuz! + jameson_manhua 插件）编写的 Tachiyomi 系阅读器扩展。

- 语言：zh（中文）
- 内容分级：NSFW（站点为成人/紳士向漫画）
- libVersion：1.6（KeiSource 新 API）
- 参考实现：`keiyoushi/extensions-source` 的 `wnacg`（Discuz 成人源）、`picacomic`（账号密码登录）、`Komga`（自定义地址 + 自动登录）

## 功能

| 能力 | 说明 |
|---|---|
| 最新更新 | 漫画库 `a=ku&odfie=addtime&order=desc`，每页 60 条 |
| 分类浏览 | 同人誌 / 單行本 / 雜誌&短篇 / 韓漫 / 3D漫畫 / 漢化 |
| 搜索 | 站内搜索（keyword），关键词分页 |
| 详情 | 标题、作者、分类、标签、封面 |
| 阅读 | 阅读页 JS `let urls = [...]` 数组直接取图（webp/jpg，CDN 域 imgspace.co.uk） |
| 自定义网站地址 | 扩展设置 → “网站地址”，默认 `https://www.itsacgaa.online` |
| 账号密码登录 | 扩展设置 → 用户名 / 密码 → “登录”；填写后浏览时自动登录，会话 Cookie 持久保存；也可手动点击登录/退出登录 |

## 安装（构建）

本模块需放在 keiyoushi/extensions-source 仓库的 `src/zh/` 目录下作为独立 Gradle 模块：

```
extensions-source/src/zh/itsacgaa/
├── build.gradle.kts
├── res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
└── src/eu/kanade/tachiyomi/extension/zh/itsacgaa/
    ├── ItSACG.kt        # 主源：浏览/搜索/详情/阅读/设置/登录
    ├── Filters.kt       # 分类过滤器
    └── Preferences.kt   # 偏好项（网站地址/用户名/密码/Cookie）与偏好项构建工具
```

然后在仓库根目录执行：

```bash
./gradlew :src:zh:itsacgaa:assemble
```

生成的 APK 位于 `src/zh/itsacgaa/build/outputs/apk/debug/`，安装到 Mihon/Tachiyomi 后，
在 浏览 → 扩展 → 本地 中启用即可。

## 登录实现说明

- 站点是 Discuz!，登录流程为：GET `member.php?mod=logging&action=login` 解析
  `form[action*="loginsubmit"]` 的 action 与 `formhash` → POST（`&inajax=1`），
  携带 `formhash/username/password/questionid=0/answer=/cookietime=2592000/referer`。
- 成功响应包含 `succeed`；失败响应为 XML `<root>登錄失敗，您還可以嘗試 N 次...</root>`。
- 会话 Cookie 前缀 `ZcL0_4a71_`，由扩展保存（SharedPreferences）并通过网络拦截器注入本站请求。
- 图片在 CDN 域（imgspace.co.uk），无需携带本站 Cookie。

### 已验证
- 列表 / 分类 / 搜索 / 详情 / 阅读页的 URL 模式、DOM 选择器、图片数组正则均以真实页面原始 HTML 逐一核对。
- 登录 POST 流程以假账号实测（返回失败 XML，格式符合预期）；成功路径为 Discuz 标准行为，
  因无真实账号未端到端验证，若登录后仍有问题可检查响应中的 `succeed` 判定。

### 注意
- 站点对非浏览器客户端有 TLS 指纹限制（curl 等会 Connection reset），本扩展在 Mihon 内使用
  OkHttp 实测行为以 App 为准；如遇访问异常，请在扩展设置中确认“网站地址”正确。
- 修改“网站地址”后建议重启 App 生效。
