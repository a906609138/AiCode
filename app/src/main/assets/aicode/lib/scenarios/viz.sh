#!/bin/sh
# 场景：报表与数据可视化（Python 3 + Pillow + 中文字体）
# 装好后 AI 可在容器内直接 import PIL 生成带中文的 PNG 图表/报表，并在对话里用
# markdown 图片语法 ![](容器内绝对路径) 内联显示（宿主 MarkdownImageTransformer 负责渲染）。
scenario_name_viz() { echo "报表/数据可视化（Python 3 + Pillow + 中文字体）"; }
install_scenario_viz() {
    ask_mirror
    echo ""
    echo "正在更新软件包列表..."
    pkg_update || { echo "${C_RED}更新软件包列表失败，请检查网络后重试。${C_RESET}"; return 1; }
    py_pkgs=$(runtime_pkgs python)
    viz_pkgs=$(viz_runtime_pkgs)
    if [ -z "$viz_pkgs" ]; then
        echo "${C_RED}当前包管理器没有可用的 Pillow / 中文字体包，跳过。${C_RESET}"
        return 1
    fi
    echo ""
    echo "══════════════ 安装清单 ══════════════"
    echo "  基础工具: $BASE_PKGS"
    echo "  Python:   $py_pkgs"
    echo "  报表支持: $viz_pkgs"
    echo "══════════════════════════════════════"
    printf "  输入 y 开始安装，其它键返回菜单: "
    read ans
    [ "$ans" = "y" ] || [ "$ans" = "Y" ] || return 2
    echo ""
    echo "开始安装（可能需要几分钟，请耐心等待）..."
    pkg_add $BASE_PKGS $py_pkgs $viz_pkgs || return 1
    return 0
}
