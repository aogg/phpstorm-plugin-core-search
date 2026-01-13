# 核心搜索 (Core Search)

[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2023.3+-blue.svg)](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html)
[![PHPStorm](https://img.shields.io/badge/PHPStorm-2023.3+-green.svg)](https://www.jetbrains.com/phpstorm/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个强大的 PhpStorm 插件，用于根据 `@core` 注解和自动发现规则高效搜索 PHP 方法调用位置。

## 功能特性

### 🔍 核心搜索 (Core Search)
- **@core 注解支持**：在方法 PHPDoc 中使用 `@core 关键词` 标记重要方法
- **智能右键菜单**：在类上右键时自动检测 @core 注解，动态显示"搜索核心"选项
- **关键词搜索**：点击关键词选项，搜索该关键词对应的方法被调用的所有位置
- **类型匹配过滤**：只显示与当前类或其子类相关的调用，支持对象调用和静态调用
- **继承链支持**：自动识别父类方法，支持跨继承链的搜索

### 🔎 自动发现 (Auto Discover)
- **规则配置**：支持通配符规则，如 `get*`, `set*`, `handle*` 等
- **智能匹配**：自动发现类中匹配规则的 public 方法
- **动态菜单**：根据当前类动态生成搜索选项
- **大小写控制**：支持大小写敏感/不敏感匹配
- **缓存优化**：高效的缓存机制，提升响应速度

### 📊 搜索结果展示
- **工具窗口集成**：结果显示在 PhpStorm 的"查找"工具窗口
- **详细信息展示**：显示调用位置、方法名、类名等详细信息
- **快速导航**：点击结果直接跳转到代码位置

## 安装方法

### 从 JetBrains Marketplace 安装
1. 打开 PhpStorm
2. 进入 `File` → `Settings` → `Plugins`
3. 点击 `Marketplace` 标签
4. 搜索 "Core Search"
5. 点击 `Install` 并重启 IDE

### 手动安装
1. 从 [Releases](../../releases) 页面下载最新版本的 `core-search-*.zip` 文件
2. 打开 PhpStorm
3. 进入 `File` → `Settings` → `Plugins`
4. 点击齿轮图标 → `Install Plugin from Disk...`
5. 选择下载的 zip 文件并安装

## 使用说明

### 核心搜索功能

#### 1. 添加 @core 注解
在 PHP 方法的 PHPDoc 注释中添加 `@core` 注解：

```php
<?php
class UserService {
    /**
     * 用户登录验证
     * @core login
     * @core auth
     */
    public function login($username, $password) {
        // 登录逻辑
    }

    /**
     * 用户注册
     * @core register
     */
    public function register($userData) {
        // 注册逻辑
    }
}
```

#### 2. 使用右键菜单搜索
1. 在 PhpStorm 中打开包含类的 PHP 文件
2. 在类名上右键
3. 如果类或其父类包含 @core 注解，会显示"搜索核心"选项
4. 鼠标悬停在"搜索核心"上，会显示可用的关键词选项
5. 点击关键词（如 "login"）执行搜索

#### 3. 查看搜索结果
搜索结果会在"查找"工具窗口中显示，包含：
- 调用该方法的文件和行号
- 调用代码的预览
- 快速跳转链接

### 自动发现功能

#### 1. 配置规则
1. 进入 `File` → `Settings` → `Tools` → `Core Search - Auto Discover`
2. 配置匹配规则，如：
   - `get*` - 匹配所有 get 开头的方法
   - `set*` - 匹配所有 set 开头的方法
   - `handle*` - 匹配所有 handle 开头的方法
3. 设置是否忽略大小写

#### 2. 使用自动发现
1. 在类上右键
2. 选择"搜索核心" → "自动发现"
3. 系统会自动检测类中匹配规则的 public 方法
4. 点击匹配的规则执行搜索

## 配置选项

### 自动发现设置
- **规则列表 (Rules)**：配置方法名匹配规则，支持通配符 `*`
- **大小写敏感 (Case Insensitive)**：是否忽略大小写进行匹配
- **默认规则**：`["get*", "set*", "is*", "has*", "handle*"]`

### 调试选项
插件会在 `.idea/plugin/core-search/logs/debug.log` 中记录调试信息，便于问题排查。

## 系统要求

- **IDE**: PhpStorm 2023.3+
- **JDK**: 17+
- **操作系统**: Windows, macOS, Linux

## 开发与贡献

### 构建项目
```bash
# 克隆项目
git clone https://github.com/aogg/phpstorm-plugin-core-search.git
cd phpstorm-plugin-core-search

# 使用 Gradle 构建
./gradlew buildPlugin

# 构建产物位于 build/distributions/
```

### 开发环境设置
1. 确保安装 JDK 17+
2. 安装 IntelliJ IDEA Ultimate 或 PhpStorm
3. 导入项目作为 Gradle 项目
4. 配置 Plugin SDK 为 PhpStorm

### 贡献指南
欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 作者

**aogg** - [GitHub](https://github.com/aogg)

## 致谢

感谢 JetBrains 提供优秀的 IntelliJ Platform，让这个插件成为可能。

---

如果您发现任何问题或有功能建议，请在 [Issues](../../issues) 页面提交。