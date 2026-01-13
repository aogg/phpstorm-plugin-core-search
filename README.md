# 核心搜索 (Core Search)

一个强大的 PhpStorm 插件,用于根据 `@core` 注解智能搜索 PHP 方法调用位置,并支持自动发现功能。

## ✨ 功能特性

### 🎯 核心搜索
- 🔍 **@core 注解解析**: 自动识别方法 PHPDoc 中的 `@core` 关键词注解
- 📋 **动态菜单生成**: 在类右键菜单中动态显示"搜索核心"选项及关键词子菜单
- 🔎 **智能搜索**: 支持按关键词搜索调用当前类方法的位置
- 🌳 **继承链支持**: 自动收集当前类及所有父类的 `@core` 关键词
- 🎯 **精准过滤**: 只显示与当前类相关的调用位置(对象类型或静态调用类为当前类或其子类)

### 🤖 自动发现
- 🔍 **模式匹配**: 根据配置的方法名规则自动发现方法(如 `get*`, `set*`, `is*`)
- ⚙️ **可配置规则**: 支持自定义通配符规则,灵活匹配方法名
- 🔄 **大小写控制**: 可选择是否忽略大小写
- 💾 **智能缓存**: 基于文件修改时间戳的高效缓存机制
- 🎨 **可视化结果**: 搜索结果展示在专用工具窗口中,支持代码预览和快速导航

## 📥 安装

1. 打开 PhpStorm
2. 进入 `File` -> `Settings` -> `Plugins`
3. 点击 `⚙️` -> `Install Plugin from Disk...`
4. 选择下载的 `.zip` 插件包
5. 重启 PhpStorm

## 🚀 使用方法

### 使用 @core 注解

在 PHP 方法的 PHPDoc 注释中添加 `@core` 注解:

```php
<?php
class UserService {
    /**
     * 用户登录
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
    public function register($data) {
        // 注册逻辑
    }
}
```

### 搜索方法调用

1. 在 PHP 类文件中,右键点击类名或类内任意位置
2. 选择 `搜索核心` 菜单项
3. 在子菜单中选择要搜索的关键词(如 `login`, `register`)
4. 查看搜索结果面板,显示所有匹配的方法调用位置

### 使用自动发现

1. 右键点击类名
2. 选择 `搜索核心` -> `自动发现`
3. 在三级菜单中选择方法名规则(如 `get*`, `set*`)
4. 插件会自动搜索匹配规则的所有 public 方法调用位置

### 配置自动发现规则

1. 进入 `File` -> `Settings` -> `Tools` -> `核心搜索 - 自动发现`
2. 添加或修改方法名规则(支持 `*` 通配符)
3. 设置是否忽略大小写匹配
4. 点击 `Apply` 保存配置

## 📖 详细说明

### @core 注解语法

- **格式**: `@core 关键词`
- **位置**: 方法的 PHPDoc 注释块中
- **可见性**: 只有 `public` 方法的注解会被识别
- **多个关键词**: 一个方法可以有多个 `@core` 注解

```php
/**
 * 处理支付
 * @core payment
 * @core transaction
 * @core billing
 */
public function processPayment($method) {
    // ...
}
```

### 搜索规则

#### 方法收集策略
1. 优先从当前类及其父类收集包含关键词的方法
2. 如果未找到,则在全项目范围内搜索
3. 使用 StubIndex 进行高效索引查找

#### 类型匹配规则
1. **对象调用**: 检查调用对象的 PHP 类型是否为当前类或其子类
2. **静态调用**: 检查调用类名是否匹配当前类或其子类
3. **兜底规则**: 类名相同也认为相关(处理不同命名空间的同名类)

#### 过滤逻辑
只保留与当前类相关的调用:

```php
// ✅ 会被找到
$service = new UserService();
$service->login('user', 'pass');

// ✅ 会被找到(子类调用)
class AdminService extends UserService {}
$admin = new AdminService();
$admin->login('admin', 'pass');

// ✅ 会被找到(静态调用)
UserService::validateUser(123);

// ❌ 不会被找到(无关类型)
$orderService = new OrderService();
$orderService->login(123);
```

### 自动发现规则

#### 匹配逻辑
1. **通配符转换**: `*` 转换为正则表达式 `.*`
2. **正则匹配**: 使用 `^pattern$` 进行完整匹配
3. **前缀回退**: 如果规则以 `*` 结尾,支持前缀匹配
4. **大小写处理**: 根据配置决定是否忽略大小写

#### 示例规则
- `get*` - 匹配所有以 `get` 开头的方法
- `set*` - 匹配所有以 `set` 开头的方法
- `is*` - 匹配所有以 `is` 开头的方法
- `handle*` - 匹配所有以 `handle` 开头的方法
- `*Async` - 匹配所有以 `Async` 结尾的方法

## 🔧 系统要求

- **IDE**: PhpStorm 2023.3 及以上版本
- **JDK**: Java 17 或更高版本
- **插件依赖**: 
  - IntelliJ Platform
  - PHP Plugin

## 📝 开发信息

### 技术栈
- **语言**: Kotlin 1.9.22
- **构建工具**: Gradle
- **框架**: IntelliJ Platform SDK

### 项目结构
```
src/main/
├── java/com/aogg/core/search/
│   ├── action/          # 动作类(右键菜单、搜索触发)
│   ├── helper/          # 辅助工具类
│   ├── model/           # 数据模型
│   ├── settings/        # 配置管理
│   └── ui/              # 用户界面组件
└── resources/
    └── META-INF/
        └── plugin.xml   # 插件配置文件
```

### 构建插件

```bash
# Windows
./gradlew.bat buildPlugin

# 插件包位于
build/distributions/core-search-*.zip
```

## 📄 许可证

本项目采用开源许可证。

## 👤 作者

- **作者**: aogg
- **网站**: [https://github.com/aogg](https://github.com/aogg)

## 🐛 问题反馈

如果您遇到任何问题或有功能建议,请在 [GitHub Issues](https://github.com/aogg/phpstorm-plugin-core-search/issues) 中提交。

## 🙏 致谢

感谢使用核心搜索插件!如果觉得有用,欢迎 Star ⭐