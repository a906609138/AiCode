---
name: data-visualization
description: 图表选型、绘制与检查的流程。做数据可视化、报告配图、优化现有图表时使用：先定要表达的关系再选图型，先探测绘图环境再选实现路径，画完自检。
---

# 数据可视化流程

## 何时使用

- 需要把数据画成图；
- 现有图表说不清问题，想优化；
- 报告、汇报需要配图。

## 步骤

1. **先定「要表达什么关系」**，再选图型：

   | 想表达 | 用什么图 | 注意 |
   | --- | --- | --- |
   | 比大小 | 条形图（类别多时横向） | 按数值排序更易读 |
   | 看趋势 | 折线图 | 时间轴要均匀 |
   | 看构成 | 堆叠条形 / 饼图 | 饼图类别 ≤ 5，否则用条形 |
   | 看分布 | 直方图 / 箱线图 | 说明分箱方式 |
   | 看相关 | 散点图 | 别把相关说成因果 |
   | 两维差异 | 热力图 | 标注色阶含义 |

2. **探测绘图环境**（别假设库已装）：
   ```sh
   python3 -c "import PIL; print('pillow', PIL.__version__)" 2>/dev/null
   python3 -c "import matplotlib; print('matplotlib', matplotlib.__version__)" 2>/dev/null
   ```
   - **有 Pillow / matplotlib** → 出 PNG；
   - **都没有** → 优先生成**自包含 HTML（内联 SVG/Canvas）**或纯 SVG 文件——无需装任何东西，浏览器与 App 的 WebView 都能直接打开。不要为了画图先让用户装一堆依赖。
   - 用户想要 PNG 报表但环境缺 Pillow / 中文字体时，提示：终端运行 `aicode` → 选「报表/数据可视化（Python 3 + Pillow + 中文字体）」场景安装。

3. **绘制要点**：
   - 标题写**结论**：「Q2 销量环比 +18%」，而不是「销量图」；
   - 坐标轴标单位；关键数值直接标在图上，别让读者估；
   - 配色克制：同一含义同一颜色；类别多时用同色系深浅；
   - 去掉无意义的网格线、3D 效果、阴影。
   - **Pillow 画中文**：字体不能省，缺字体中文全变方框；用 glob 探测真实路径，别硬编码：
     ```python
     import glob
     from PIL import ImageFont
     cands = (glob.glob('/usr/share/fonts/**/*wqy*', recursive=True)
              + glob.glob('/usr/share/fonts/**/*CJK*', recursive=True))
     font = ImageFont.truetype(cands[0], 24) if cands else ImageFont.load_default()
     ```
     PNG 存到工作区（如 `~/workspace/report.png`）。

4. **自检**：生成后用 `viewImage` 实际看一眼——文字有没有重叠、标签有没有被截断、图例有没有挡住数据、坐标轴范围是否误导。

5. **交付**：正文用 markdown 图片语法内联显示本地图片（宿主会经 `FileAccessProvider` 落到本地再渲染，点一下可全屏看原图）：
   ```markdown
   ![](~/workspace/report.png)
   ```
   路径写**容器视角**（`~/workspace/...` 或容器绝对路径，相对路径也行），并附一句读图说明。非图片文件（HTML/SVG）用 `sendFile` 给出。

## 注意

- **绝不编造数据点**。数据不全就说明缺什么，不要用「示意数据」冒充真实数据。
- 不用误导性可视化：截断坐标轴夸大差异、用面积/体积表达一维数据、只展示有利区间。
- 不堆砌装饰；准确与清晰优先于美观。
