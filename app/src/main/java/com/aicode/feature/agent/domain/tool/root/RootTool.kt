package com.aicode.feature.agent.domain.tool.root

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.root.RootManager
import com.aicode.feature.agent.domain.root.RootState
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.RootToolSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * 通过宿主 Android root（UID 0）执行命令的工具。
 *
 * 三种执行身份中最高的一个：容器（PRoot，伪 root）→ Shizuku（adb shell，uid 2000）→ 本工具（UID 0）。
 * 能访问其它应用私有目录与系统分区，属最高危能力，仅在确有必要时使用；常规开发命令用 `Bash`。
 *
 * 需用户在「设置 → 软件权限」开启 Root 工具，且每次调用都弹窗确认（不可记忆）。
 */
class RootTool @Inject constructor(
    private val rootManager: RootManager,
    private val rootToolSettings: RootToolSettingsRepository
) : AgentTool() {
    private companion object {
        const val TAG = "RootTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Root"

    override val description =
        "以宿主 Android root（UID 0）身份执行一次性 Shell 命令。" +
            "可访问其它应用私有目录与系统分区，权限最高、风险最大；" +
            "需用户已在「设置 → 软件权限」开启 Root 工具，默认每次调用需确认。" +
            "常规开发命令请用 Bash，不要用本工具。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要通过 root 执行的 Shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认以 Root 身份执行命令",
            summary = command,
            details = "将以宿主 Android root（UID 0）身份执行，可读写系统分区与其它应用私有目录。\n超时：$timeoutSeconds 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：command")

        // 开关默认关闭：未在「设置 → 软件权限」开启时直接拒绝，不探测也不执行
        if (!rootToolSettings.isEnabled()) {
            return ToolResult.Error(
                "Root 工具未开启：请先在「设置 → 软件权限 → Root 工具」中开启",
                code = "ROOT_TOOL_DISABLED"
            )
        }

        val state = rootManager.state.value
        if (state != RootState.READY) {
            return ToolResult.Error(
                "Root 不可用：设备未 root，或尚未在「设置 → 软件权限」授予本应用 root 权限",
                code = "ROOT_NOT_READY"
            )
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "root exec (timeout=${timeoutMs}ms): $command")
            val result = rootManager.runCommand(command, timeoutMs)
            FileLogger.v(TAG, "root exec 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(result.output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "执行 root 命令失败: $command", e)
            ToolResult.Error("执行 root 命令失败：${e.message}")
        }
    }
}
