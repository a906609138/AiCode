package com.aicode.feature.agent.data.remote.skillstore

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skill.GlobalDirectorySkillSource
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 安装结果。[installedFiles] 为实际写入的文件数，供 UI 反馈。 */
data class SkillInstallResult(
    val skillName: String,
    val installedFiles: Int,
    val skippedFiles: List<String> = emptyList()
)

/**
 * 把商店技能装进本地技能目录。
 *
 * 流程：取文件清单 → 逐个下载正文 → 按清单里的 sha256 校验 → 写入 `~/.aicode/skills/<slug>/`。
 *
 * 设计取舍：
 * - **先全部下载并校验，再落盘**。中途失败不留半包，避免出现「装了一半、AI 加载到缺引用」的技能。
 * - 目录名用 slug，与 `/命令` 和技能列表一致；已存在则覆盖（用户主动点安装视为同意更新）。
 * - 单文件大小上限与总量上限沿用导入器的量级，防止异常响应写爆磁盘。
 */
@Singleton
class SkillStoreInstaller @Inject constructor(
    private val api: SkillHubApi,
    private val fileAccess: FileAccessProvider,
    private val globalSkillSource: GlobalDirectorySkillSource
) {
    private companion object {
        const val TAG = "SkillStoreInstaller"

        /** 单文件上限：技能正文都是 Markdown，1 MB 足够。 */
        const val MAX_FILE_BYTES = 1L * 1024 * 1024

        /** 单技能总上限。 */
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
    }

    /**
     * 安装指定技能。
     *
     * @param namespace 技能的 namespace（如 `@kunlungrowth`），接口必需
     */
    suspend fun install(
        slug: String,
        namespace: String
    ): SkillStoreResult<SkillInstallResult> {
        val files = when (val result = api.files(slug, namespace)) {
            is SkillStoreResult.Failure -> return result
            is SkillStoreResult.Success -> result.data
        }
        if (files.isEmpty()) return SkillStoreResult.Failure("技能没有可安装的文件")
        if (files.sumOf { it.size } > MAX_TOTAL_BYTES) {
            return SkillStoreResult.Failure("技能体积超出上限，已取消安装")
        }

        // 1) 全部下载 + 校验，任一失败即中止，不落盘
        val payload = mutableListOf<Pair<SkillStoreFile, String>>()
        for (file in files) {
            if (file.size > MAX_FILE_BYTES) {
                return SkillStoreResult.Failure("文件过大，已取消安装：${file.path}")
            }
            val content = when (val result = api.fileContent(slug, namespace, file.path)) {
                is SkillStoreResult.Failure -> return SkillStoreResult.Failure("下载失败：${file.path}（${result.message}）")
                is SkillStoreResult.Success -> result.data
            }
            if (file.sha256.isNotBlank() && sha256(content) != file.sha256) {
                FileLogger.w(TAG, "文件校验不一致: ${file.path}")
                return SkillStoreResult.Failure("文件校验失败，已取消安装：${file.path}")
            }
            payload += file to content
        }

        // 2) 落盘到 ~/.aicode/skills/<slug>/
        val root = "${globalSkillSource.skillsRoot.trimEnd('/')}/$slug"
        var written = 0
        return try {
            for ((file, content) in payload) {
                // 防御路径穿越：清单里的 path 只能是相对路径，不允许跳出技能目录
                val rel = file.path.trim().removePrefix("/")
                if (rel.isEmpty() || rel.contains("..")) {
                    return SkillStoreResult.Failure("文件路径非法，已取消安装：${file.path}")
                }
                fileAccess.writeFile("$root/$rel", content, overwrite = true)
                written++
            }
            // 让技能目录缓存失效，安装后列表立即能看到
            fileAccess.invalidateAgentConfigCache(globalSkillSource.skillsRoot)
            SkillStoreResult.Success(SkillInstallResult(slug, written))
        } catch (e: Exception) {
            FileLogger.e(TAG, "写入技能文件失败: $slug", e)
            SkillStoreResult.Failure("写入技能文件失败：${e.message}")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 从已获取的正文安装：供多源商店用。
     *
     * 各源的文件清单、哈希、下载方式各不相同，由各自的 [SkillStoreSource] 消化后交给这里，
     * 落盘阶段统一只做体积限制与路径穿越防御。源不给哈希时不做校验。
     *
     * @param sha256ByPath 路径到预期 sha256；为空表示该源不提供哈希
     */
    suspend fun installFromContents(
        slug: String,
        contents: Map<String, String>,
        sha256ByPath: Map<String, String> = emptyMap()
    ): SkillStoreResult<SkillInstallResult> {
        if (contents.isEmpty()) return SkillStoreResult.Failure("技能没有可安装的文件")
        if (contents.values.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_TOTAL_BYTES) {
            return SkillStoreResult.Failure("技能体积超出上限，已取消安装")
        }
        for ((path, content) in contents) {
            if (content.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
                return SkillStoreResult.Failure("文件过大，已取消安装：$path")
            }
            val expected = sha256ByPath[path]
            if (!expected.isNullOrBlank() && sha256(content) != expected) {
                FileLogger.w(TAG, "文件校验不一致: $path")
                return SkillStoreResult.Failure("文件校验失败，已取消安装：$path")
            }
        }

        val root = "${globalSkillSource.skillsRoot.trimEnd('/')}/$slug"
        var written = 0
        return try {
            for ((path, content) in contents) {
                val rel = path.trim().removePrefix("/")
                if (rel.isEmpty() || rel.contains("..")) {
                    return SkillStoreResult.Failure("文件路径非法，已取消安装：$path")
                }
                fileAccess.writeFile("$root/$rel", content, overwrite = true)
                written++
            }
            fileAccess.invalidateAgentConfigCache(globalSkillSource.skillsRoot)
            SkillStoreResult.Success(SkillInstallResult(slug, written))
        } catch (e: Exception) {
            FileLogger.e(TAG, "写入技能文件失败: $slug", e)
            SkillStoreResult.Failure("写入技能文件失败：${e.message}")
        }
    }
}