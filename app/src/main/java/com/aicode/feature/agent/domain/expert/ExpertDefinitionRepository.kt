package com.aicode.feature.agent.domain.expert

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 专家定义仓库：聚合全局（`~/.aicode/experts`）与项目级（`<工作区>/.aicode/experts`）两处定义。
 *
 * 同名专家项目级优先（与技能、子代理的覆盖语义一致）。每次调用重新扫描：专家目录通常只有几十个
 * 小文件，代价可接受，换来「手工增删文件后立即生效」的体验；扫描失败降级为空列表，不向上抛。
 */
@Singleton
class ExpertDefinitionRepository @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    private companion object {
        const val TAG = "ExpertRepository"

        /** 全局专家目录（容器路径，跟随当前执行环境）。 */
        const val GLOBAL_ROOT = "~/.aicode/experts"

        /** 项目级专家目录（相对工作区根）。 */
        const val PROJECT_SUBDIR = ".aicode/experts"
    }

    /** 列出全部专家，项目级覆盖全局同名项，按展示名排序。 */
    fun listAll(projectRoot: String? = null): List<ExpertDefinition> {
        val merged = LinkedHashMap<String, ExpertDefinition>()
        scan(GLOBAL_ROOT, ExpertDefinitionScope.GLOBAL).forEach { merged[it.name] = it }
        if (!projectRoot.isNullOrBlank()) {
            val projectDir = "${projectRoot.trimEnd('/')}/$PROJECT_SUBDIR"
            scan(projectDir, ExpertDefinitionScope.PROJECT).forEach { merged[it.name] = it }
        }
        return merged.values.sortedBy { it.title.lowercase() }
    }

    /** 按名查找（项目级优先）；不存在返回 null。 */
    fun findByName(name: String, projectRoot: String? = null): ExpertDefinition? =
        listAll(projectRoot).firstOrNull { it.name == name }

    /** 扫描单个目录下的顶层 `*.md`；单文件失败只跳过它。 */
    private fun scan(root: String, scope: ExpertDefinitionScope): List<ExpertDefinition> = runCatching {
        if (!fileAccess.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        fileAccess.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .mapNotNull { entry ->
                runCatching { ExpertDefinitionParser.parse(fileAccess, "$base/${entry.name}", scope) }
                    .onFailure { FileLogger.w(TAG, "解析专家定义失败，已跳过: ${entry.name}", it) }
                    .getOrNull()
            }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描专家目录失败: $root", e)
        emptyList()
    }
}
