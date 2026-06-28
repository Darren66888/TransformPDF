# 📄 TransformPDF — PDF/图片格式转换 & 文档扫描工具

> 基于 Vue 3 + Spring Boot 的全栈 Web 应用，支持图片转 PDF/Word、**PDF 转 Word（排版保留）**、文档扫描（透视校正 + 增强），媲美全能扫描王的扫描效果。

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)
![Vue.js](https://img.shields.io/badge/Vue.js-3.4-blue)
![Element Plus](https://img.shields.io/badge/Element%20Plus-2.7-blue)
![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

---

## ✨ 功能特性

### 📄 PDF 转 Word（重点功能）
- 基于 PDFBox + POI 实现，纯 Java 无平台限制
- **字体保留** — 提取并写入字号、粗体等格式信息
- **标题识别** — 自动检测 H1/H2/H3 标题并应用 Word 标题样式
- **图片提取** — 从 PDF 页面资源中提取嵌入图片，自动写入 Word
- **段落分组** — 按行间距智能合并文本为段落，不再是逐行拆分
- **表格检测** — 基于列间距检测（≥25pt 大间隙 + 跨行对齐验证），非简单空格分割
- **分页保留** — 原 PDF 页面边界用 Word 分页符保留
- **内联格式** — 同行中不同格式文本独立渲染

### 📸 图片转 PDF
- 支持 JPG/PNG 多张图片批量上传
- 可选择性勾选指定图片合并为一个 PDF
- 支持拖拽排序，调整图片顺序
- 缩略图预览，一键全选/反选

### 🖼️ 图片转 Word
- 将 JPG/PNG 图片嵌入 Word 文档（.docx）
- 自动适配页面尺寸，保持图片比例

### 📷 文档扫描
- **手动框选** — 点击图片后全屏编辑，拖动四角选择文档区域
- **自动检测** — Canny 边缘检测 + 轮廓查找 + Douglas-Peucker 多边形逼近
- **透视校正** — 8 参数透视变换，将倾斜文档校正为正面视角
- **智能增强**：
  - 背景归一化（消除阴影、光照不均）
  - Sauvola 自适应二值化（文字清晰黑白分明）
  - 灰度混合（保留印章、签名、照片等非文字内容）
  - 百分位对比度拉伸 + 白色背景推送
- 手动框选 + 自动检测双重模式

### 📋 历史记录管理
- 转换历史记录列表，显示状态、时间、文件类型
- 一键下载、删除记录及关联文件

---

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| **前端** | Vue 3 + Vite + Element Plus + Axios |
| **后端** | Spring Boot 3.2 + Spring Data JPA |
| **数据库** | MySQL 8.0 |
| **PDF 处理** | Apache PDFBox 3.0 |
| **Word 生成** | Apache POI 5.2 |
| **图像处理** | 纯 Java 实现（无需 OpenCV 运行环境） |

---

## 📁 项目结构

```
transformPDF/
├── backend/                                    # Spring Boot 后端
│   ├── src/main/java/com/transformpdf/
│   │   ├── TransformPdfApplication.java        # 启动入口
│   │   ├── controller/
│   │   │   ├── ConversionController.java       # REST API 控制器
│   │   │   ├── ApiResponse.java                # 统一响应封装
│   │   │   └── GlobalExceptionHandler.java     # 全局异常处理
│   │   ├── entity/
│   │   │   └── ConversionTask.java             # 数据库实体
│   │   ├── repository/
│   │   │   └── ConversionTaskRepository.java   # JPA 数据访问层
│   │   ├── service/
│   │   │   ├── ConversionService.java          # 转换服务接口
│   │   │   ├── FileStorageService.java         # 文件存储服务
│   │   │   └── impl/ConversionServiceImpl.java # 转换服务实现
│   │   ├── config/
│   │   │   └── WebConfig.java                  # CORS & 静态资源映射
│   │   └── util/
│   │       ├── ImageProcessor.java             # 图像处理核心算法（扫描增强）
│   │       └── PdfContentExtractor.java        # PDF 内容提取器（布局分析）
│   ├── src/main/resources/
│   │   ├── application.yml                     # 应用配置
│   │   └── init.sql                            # 数据库初始化脚本
│   └── pom.xml                                 # Maven 依赖
├── frontend/                                   # Vue 3 前端
│   ├── src/
│   │   ├── App.vue                             # 主界面（单文件组件）
│   │   ├── api/index.js                        # Axios API 封装
│   │   └── main.js                             # 入口文件
│   ├── index.html
│   ├── package.json
│   └── vite.config.js                          # Vite 配置（含代理）
├── .vscode/
│   └── settings.json                           # VS Code 配置
└── README.md
```

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17+ |
| Node.js | 18+ |
| MySQL | 8.0+ |
| Maven | 3.6+ |

### 1. 克隆项目

```bash
git clone https://github.com/your-username/transformPDF.git
cd transformPDF
```

### 2. 创建数据库

**方式一：执行初始化脚本（推荐）**

```bash
mysql -u root -p < backend/src/main/resources/init.sql
```

**方式二：手动创建**

```sql
CREATE DATABASE IF NOT EXISTS transformpdf
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
```

> 数据表会在后端首次启动时由 JPA（`ddl-auto: update`）自动创建。
> `init.sql` 也包含了完整的建表 DDL 供参考。

### 3. 配置数据库连接

编辑 `backend/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/transformpdf?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 134679852
```

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端默认运行在 `http://localhost:8080`，API 上下文路径为 `/api`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`，开发模式下自动代理 `/api` 请求到后端。

### 6. 访问应用

打开浏览器访问 **http://localhost:5173**

---

## 📡 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/convert/upload` | 上传文件（支持 JPG/PNG/PDF） |
| `POST` | `/api/convert/to-pdf` | 图片转 PDF |
| `POST` | `/api/convert/to-word` | **PDF 转 Word（格式保留）** |
| `POST` | `/api/convert/scan-to-image` | 自动扫描 → 输出图片 |
| `POST` | `/api/convert/scan-to-pdf` | 自动扫描 → 输出 PDF |
| `POST` | `/api/convert/scan-to-word` | 自动扫描 → 输出 Word |
| `POST` | `/api/convert/scan-doc` | 手动框选扫描（支持 image/pdf/word 输出） |
| `POST` | `/api/convert/merge-to-pdf` | 多图合并为 PDF |
| `GET` | `/api/convert/tasks` | 获取所有历史记录 |
| `GET` | `/api/convert/tasks/{id}` | 获取任务详情 |
| `GET` | `/api/convert/download/{id}` | 下载转换结果文件 |
| `GET` | `/api/convert/preview/{*filePath}` | 预览文件 |
| `DELETE` | `/api/convert/tasks/{id}` | 删除记录及关联文件 |

---

## 🔬 核心算法

### PDF → Word 转换流程

```
PDF 文件
  │
  ├─→ PdfContentExtractor（位置感知提取）
  │     ├─ 逐字符提取 TextPosition（x, y, w, h, 字号, 字体名）
  │     ├─ 按 Y 坐标 → 行分组（tolerance = 行高 × 0.6）
  │     ├─ 按 X 间隙 → 字符合并为词（gap ≤ 3.5pt = 同词, > 3.5pt = 新词）
  │     ├─ 标题识别：字号 ≥ 1.4× 正文 或 粗体 + 字号 ≥ 1.25× 正文
  │     ├─ 表格检测：同行词间间隙 ≥ 25pt 连续 4 行 + 列边界跨行一致
  │     └─ 段落分组：按空行边界合并连续文本行
  │
  └─→ POI XWPFDocument 输出
        ├─ 标题 → Heading1/2/3 样式
        ├─ 表格 → XWPFTable（含边框 + 首行粗体表头）
        ├─ 段落 → XWPFParagraph（保留字号/粗体 + 同行多 Run）
        ├─ 图片 → XWPFRun.addPicture() 嵌入
        └─ 分页 → setPageBreak(true)
```

### 文档扫描流程

```
原始照片
  │
  ├─→ 文档检测（二选一）
  │     ├─ 主方案：Canny 边缘 → 轮廓查找 → Douglas-Peucker 多边形逼近 → 找最大凸四边形
  │     └─ 备选方案：Otsu 阈值分割 → 形态学闭开运算 → 最大连通域 → 凸包 → 找四边形
  │
  ├─→ 透视校正
  │     └─ 8 参数透视矩阵（高斯消元求解）→ 双线性插值重采样
  │
  └─→ 图像增强
        ├─ 背景归一化：pixel ÷ 大核高斯模糊 × 255（去阴影）
        ├─ 百分位对比度拉伸（裁剪 1%/99% 极端值）
        ├─ Sauvola 自适应阈值（积分图加速, k=0.2, R=128）
        ├─ 灰度混合（文字区域用二值图保证清晰，非文字区保留原灰度）
        ├─ 白色背景推送（≥220 推至纯白）
        └─ Laplacian 锐化
```

### 图像处理算法（全部纯 Java 实现）

| 算法 | 实现 |
|------|------|
| Canny 边缘检测 | Sobel 梯度 → 非极大值抑制 → 双阈值滞后 |
| 轮廓查找 | 8 连通边界追踪 |
| 多边形逼近 | Douglas-Peucker 递归简化 |
| 凸包 | Andrew's Monotone Chain |
| 透视变换 | 8 参数矩阵 + 高斯消元 |
| 双线性插值 | 4 邻域加权平均 |
| 高斯模糊 | 可分离核优化（先降采样再模糊再升采样） |
| Sauvola 二值化 | 积分图加速 O(1) 局部均值/方差 |
| Otsu 阈值 | 类间方差最大化 |

---

## ⚙️ 配置说明

### 后端配置 (`application.yml`)

```yaml
server:
  port: 8080
  servlet:
    context-path: /api

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/transformpdf?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 134679852
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false

app:
  upload:
    dir: ./uploads
    max-size: 52428800
```

### 前端代理 (`vite.config.js`)

```js
server: {
  port: 5173,
  proxy: {
    '/api': 'http://localhost:8080'
  }
}
```

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m 'Add your feature'`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

---

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue.js](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [Apache PDFBox](https://pdfbox.apache.org/)
- [Apache POI](https://poi.apache.org/)

---

> ⭐ 如果这个项目对你有帮助，请给个 Star 支持一下！
