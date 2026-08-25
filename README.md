<div align="center">

# 神殇漫画

### 基于 Mihon 的 Komga 漫画客户端

内置 Komga 服务器连接，支持多媒体库目录树浏览、视频/音频播放，启动即登录。

[![CI](https://img.shields.io/github/actions/workflow/status/shenshangshang/mihon/build.yml?label=Build&labelColor=27303D)](https://github.com/shenshangshang/mihon/actions)
[![Release](https://img.shields.io/github/v/release/shenshangshang/mihon?label=Release&labelColor=06599d&color=043b69)](https://github.com/shenshangshang/mihon/releases)
[![License: Apache-2.0](https://img.shields.io/github/license/shenshangshang/mihon?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

## 下载

[![Release](https://img.shields.io/github/v/release/shenshangshang/mihon.svg?label=下载&labelColor=06599d&color=043b69)](https://github.com/shenshangshang/mihon/releases)

*需要 Android 8.0 或更高版本。*

安装 `app-arm64-v8a-debug` 版本（现代手机均为 arm64），旧设备安装 `app-universal-debug`。

## 功能

- **内置服务器**：预配置 `https://komga.shenshang.online`，无需手动填写地址
- **启动登录**：打开 app 输入 Komga 账号密码即可使用
- **多媒体库**：底栏"书城"tab 显示所有媒体库，点击进入目录树浏览
- **目录树导航**：文件夹卡片 + 面包屑导航，与 Komga 网页端体验一致
- **视频/音频支持**：VIDEO/AUDIO 类型书籍可直接流媒体播放
- **扩展管理**：原 Mihon 浏览功能移至"更多"页面（扩展管理 + 迁移）
- **上游自动合并**：每日自动合并 mihonapp/mihon 和 keiyoushi/extensions 更新

## 使用方法

1. 从 [Releases](https://github.com/shenshangshang/mihon/releases) 下载 APK 安装
2. 打开 app，输入 Komga 服务器用户名和密码
3. 登录后点击底栏"书城"tab 浏览媒体库
4. 点击媒体库卡片进入目录树，点击文件夹卡片进入子目录
5. 点击漫画卡片进入详情页，选择章节阅读
6. 在"更多 → 设置 → 神殇漫画"中可修改账号密码

## 架构

- `source-komga/`：内置 Komga Source 模块（Metro DI 注入）
  - `KomgaSource`：实现 `Source, UnmeteredSource`，目录树逻辑
  - `KomgaApi`：OkHttp 封装的 Komga REST API
  - `KomgaPreferences`：服务器凭据存储（PreferenceStore）
- `app/`：修改的 Mihon 应用
  - `KomgaTab`：底栏第 4 个 tab（书城）
  - `KomgaLoginScreen`：启动登录界面
  - `BrowseSourceScreen`：面包屑导航 + 目录卡片拦截
  - `SettingsKomgaScreen`：服务器凭据设置页
  - `ExtensionsScreen` / `MigrationScreen`：从浏览 tab 移出的独立页面

## CI/CD

- **build.yml**：push/PR 时自动构建 debug APK
- **release.yml**：打 `v*` tag 时自动构建并发布 Release
- **sync-upstream.yml**：每日 08:00 UTC 自动合并上游更新

## 上游

基于 [Mihon](https://github.com/mihonapp/mihon) 修改，遵循 Apache-2.0 许可证。
