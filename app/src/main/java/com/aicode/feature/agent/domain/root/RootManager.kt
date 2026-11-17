package com.aicode.feature.agent.domain.root

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root 可用状态，供设置页展示与工具执行前判定。
 *
 * 与 [com.aicode.feature.agent.domain.shizuku.ShizukuState] 的区别：Shizuku 走 adb shell（uid 2000），
 * 本状态对应宿主 UID 0，权限更高、风险更大，故只有两态——要么完全可用，要么不可用。
 */
enum class RootState {
    /** 未找到 su，或 `su -c id -u` 未返回 0：设备未 root 或未授予本应用 root 权限。 */
    NOT_AVAILABLE,

    /** `su -c id -u` 返回 0，可执行命令。 */
    READY
}

/** 一次 root 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class RootCommandResult(val output: String, val exitCode: Int)

/**
 * Root 执行后端：以宿主 Android root（UID 0）身份执行一次性命令。
 *
 * 与容器（PRoot）、Shizuku（adb shell）并列的第三种执行身份，能力最强：可读写其它应用私有目录
 * （`/data/data/...`）与系统分区。因此默认关闭，需用户在「设置 → 软件权限」显式开启，
 * 且工具侧一律逐次弹窗确认（见 [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine]）。
 *
 * 通过 `su -c <command>` 执行：不依赖 binder 服务，也不需要常驻进程，
 * 每次调用起一个 `su` 子进程，等待其结束并回收输出。
 */
@Singleton
class RootManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "RootManager"

        /** 常见 su 路径：AOSP、Magisk、KernelSU、APatch。 */
        val SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/su/bin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )

        /** 探测超时：root 授权弹窗可能等待用户点击，给足时间但不能无限等。 */
        const val PROBE_TIMEOUT_MS = 10_000L
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val MAX_TIMEOUT_MS = 1_800_000L
    }

    private val _state = MutableStateFlow(RootState.NOT_AVAILABLE)

    /** 当前 root 可用状态；设置页与工具据此响应。 */
    val state: StateFlow<RootState> = _state.asStateFlow()

    /** 探测到的 su 路径；null 表示尚未探测或不存在。 */
    @Volatile
    private var suPath: String? = null

    /** 重新探测 root 状态（幂等，可在设置页每次进入时调用）。 */
    suspend fun refreshState() {
        _state.value = probe()
    }

    /** 探测逻辑：找到 su 且 `su -c id -u` 输出 0 才算 READY。 */
    private suspend fun probe(): RootState = withContext(Dispatchers.IO) {
        val candidate = SU_CANDIDATES.firstOrNull { File(it).exists() }
        if (candidate == null) {
            suPath = null
            FileLogger.d(TAG, "未找到 su，判定为无 root")
            return@withContext RootState.NOT_AVAILABLE
        }
        suPath = candidate
        val result = runCatching { execSu(candidate, "id -u", PROBE_TIMEOUT_MS) }
        val ok = result.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "0" } == true
        FileLogger.d(TAG, "root 探测：su=$candidate ok=$ok")
        if (ok) RootState.READY else RootState.NOT_AVAILABLE
    }

    /**
     * 执行 root 命令。未就绪时抛异常，由调用方转成工具错误。
     *
     * @throws IllegalStateException root 不可用
     */
    suspend fun runCommand(command: String, timeoutMs: Long): RootCommandResult {
        if (_state.value != RootState.READY) {
            // 状态可能因授权被撤销而过期，执行前再探测一次
            refreshState()
            if (_state.value != RootState.READY) {
                throw IllegalStateException("Root 不可用：设备未 root 或未授予本应用 root 权限")
            }
        }
        val su = suPath ?: throw IllegalStateException("Root 不可用：未找到 su")
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        return withContext(Dispatchers.IO) { execSu(su, command, timeout) }
    }

    /** 起一个 `su -c <command>` 子进程，读输出并等待结束；超时强杀。 */
    private fun execSu(su: String, command: String, timeoutMs: Long): RootCommandResult {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = BoundedOutput()
        try {
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    output.append(String(buf, 0, n))
                }
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootCommandResult(
                    output = output.build() + "\n[命令超时，已强制终止]",
                    exitCode = -1
                )
            }
            return RootCommandResult(output.build(), process.exitValue())
        } finally {
            process.destroy()
        }
    }
}
