package com.aicode.feature.agent.domain.prompt

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「破甲」置顶提示词：读取 `~/.aicode/pj.md`，由 [SystemPromptProvider] 拼在系统提示词最前。
 *
 * 与提示词片段体系的区别（刻意如此）：
 * - 文件与 `prompts/` 平级，避开启动时全量覆盖释放的 prompts 目录；
 * - 不参与 `<两位数字>-<名称>.md` 编号，不出现在片段列表，也不被项目层覆盖；
 * - 开关独立于 `.no-builtin`，禁用内置片段时仍然注入。
 *
 * 开关状态由 App 启动时读首帧设置写入缓存、设置页切换时更新，供同步组装系统提示词使用
 * （与 [com.aicode.feature.settings.data.repository.ExecutionModeHolder] 同一模式）。
 */
@Singleton
class BreakjailPromptProvider @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "BreakjailPrompt"
        const val FILE_NAME = "pj.md"
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }

    @Volatile
    private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun file(): File = File(containerInstaller.aicodeDir, FILE_NAME)

    /** 开关开启且文件存在非空时返回正文，否则返回 null。每次读盘，编辑后立即生效。 */
    fun buildNow(): String? {
        if (!enabled) return null
        val target = file()
        if (!target.isFile) {
            FileLogger.w(TAG, "破甲已开启，但文件不存在：${target.absolutePath}")
            return null
        }
        val text = runCatching { target.readText() }
            .getOrNull()
            ?.replace(LEADING_COMMENT, "")
            ?.trim()
        if (text.isNullOrEmpty()) {
            FileLogger.w(TAG, "破甲文件为空：${target.absolutePath}")
            return null
        }
        if (text.length != lastLoggedLength) {
            lastLoggedLength = text.length
            FileLogger.d(TAG, "破甲已注入 ${text.length} 字符（先于专家块与静态基线）")
        }
        return text
    }

    @Volatile
    private var lastLoggedLength: Int = -1

    /**
     * 供设置页展示的就绪状态：开关、文件、字节数一目了然，
     * 「开关开了但文件不存在」这类问题不必去翻日志。
     */
    fun describe(): String {
        if (!enabled) return "未开启"
        val target = file()
        if (!target.isFile) return "已开启，但文件不存在：${target.absolutePath}"
        val bytes = runCatching { target.length() }.getOrDefault(0L)
        return "已开启 · ${bytes} 字节 · ${target.absolutePath}"
    }
}
