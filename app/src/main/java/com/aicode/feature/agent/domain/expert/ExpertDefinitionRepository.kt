package com.aicode.feature.agent.domain.expert

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ExpertRepository"

        /** 全局专家目录（容器路径，跟随当前执行环境）。 */
        const val GLOBAL_ROOT = "~/.aicode/experts"

        /** 项目级专家目录（相对工作区根）。 */
        const val PROJECT_SUBDIR = ".aicode/experts"

        /** 置顶清单文件名（与 AiMini 一致：纯文本，每行一个专家名）。 */
        const val PINNED_FILE = "pinned"

        /** 禁用清单文件名。 */
        const val DISABLED_FILE = "disabled"

        /** 专家名允许的字符：与文件名需一一对应，不收集中文与空格。 */
        val NAME_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
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

    /** 按作用域算出目录路径；项目级需要工作区根。 */
    private fun dirFor(scope: ExpertDefinitionScope, projectRoot: String?): String? =
        when (scope) {
            ExpertDefinitionScope.GLOBAL -> GLOBAL_ROOT
            ExpertDefinitionScope.PROJECT ->
                projectRoot?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/$PROJECT_SUBDIR" }
        }

    /** 保存（新建或覆盖）一个专家定义；同名文件已存在时直接覆盖。 */
    fun save(
        definition: ExpertDefinition,
        scope: ExpertDefinitionScope,
        projectRoot: String? = null
    ): Result<Unit> = runCatching {
        val dir = dirFor(scope, projectRoot)
            ?: error("未选择工作区，无法保存到项目级")
        val name = definition.name.trim()
        require(name.isNotEmpty()) { "专家名不能为空" }
        require(name.matches(NAME_PATTERN)) { "专家名只能包含字母、数字、下划线与连字符" }
        val path = "$dir/$name.md"
        fileAccess.writeFile(path, ExpertDefinitionWriter.write(definition), overwrite = true)
        fileAccess.invalidateAgentConfigCache(dir)
    }

    /**
     * 删除一个专家定义。
     *
     * 内置专家（`assets/experts/` 里有同名资产的）不允许删除，只能禁用：
     * 否则 App 升级时的释放逻辑会把用户删掉的又拉回来，行为与用户预期冲突。
     */
    fun delete(
        name: String,
        scope: ExpertDefinitionScope,
        projectRoot: String? = null,
        builtinNames: Set<String>
    ): Result<Unit> = runCatching {
        require(name !in builtinNames) { "内置专家不可删除，请改用禁用" }
        val dir = dirFor(scope, projectRoot)
            ?: error("未选择工作区，无法删除项目级专家")
        val path = "$dir/$name.md"
        if (!fileAccess.exists(path)) error("专家不存在：$name")
        fileAccess.delete(path)
        fileAccess.invalidateAgentConfigCache(dir)
        // 同时从置顶/禁用清单里摘掉，不留悬空名字
        unlistFrom(dir, PINNED_FILE, name)
        unlistFrom(dir, DISABLED_FILE, name)
    }

    /** 读取置顶名单（全局 + 项目级并集，小写）。 */
    fun pinned(projectRoot: String? = null): Set<String> = readNameList(PINNED_FILE, projectRoot)

    /** 读取禁用名单（全局 + 项目级并集，小写）。 */
    fun disabled(projectRoot: String? = null): Set<String> = readNameList(DISABLED_FILE, projectRoot)

    /** 切换置顶。清单按作用域分文件存放，项目级优先写项目目录。 */
    fun setPinned(name: String, pinned: Boolean, scope: ExpertDefinitionScope, projectRoot: String? = null): Result<Unit> =
        setListed(name, pinned, scope, projectRoot, PINNED_FILE)

    /** 切换禁用。 */
    fun setDisabled(name: String, disabled: Boolean, scope: ExpertDefinitionScope, projectRoot: String? = null): Result<Unit> =
        setListed(name, disabled, scope, projectRoot, DISABLED_FILE)

    /** 内置专家名单：assets 里有同名资产即算内置。 */
    fun builtinNames(): Set<String> = runCatching {
        val entries = context.assets.list("experts").orEmpty()
        entries.filter { it.endsWith(".md", ignoreCase = true) }
            .map { it.removeSuffix(".md").lowercase() }
            .toSet()
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取内置专家清单失败", e)
        emptySet()
    }

    private fun setListed(
        name: String,
        listed: Boolean,
        scope: ExpertDefinitionScope,
        projectRoot: String?,
        fileName: String
    ): Result<Unit> = runCatching {
        val dir = dirFor(scope, projectRoot)
            ?: error("未选择工作区，无法写入项目级清单")
        val key = name.trim().lowercase()
        require(key.isNotEmpty()) { "专家名不能为空" }
        val path = "$dir/$fileName"
        val current = readListFile(path)
        val updated = if (listed) current + key else current - key
        if (updated.isEmpty()) {
            if (fileAccess.exists(path)) fileAccess.delete(path)
        } else {
            fileAccess.writeFile(path, updated.sorted().joinToString("\n") + "\n", overwrite = true)
        }
        fileAccess.invalidateAgentConfigCache(dir)
    }

    private fun readNameList(fileName: String, projectRoot: String?): Set<String> {
        val names = mutableSetOf<String>()
        names += readListFile("$GLOBAL_ROOT/$fileName")
        projectRoot?.takeIf { it.isNotBlank() }?.let { names += readListFile("${it.trimEnd('/')}/$PROJECT_SUBDIR/$fileName") }
        return names
    }

    private fun readListFile(path: String): Set<String> = runCatching {
        if (!fileAccess.exists(path)) return@runCatching emptySet()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取清单失败: $path", e)
        emptySet()
    }

    private fun unlistFrom(dir: String, fileName: String, name: String) {
        val path = "$dir/$fileName"
        runCatching {
            if (!fileAccess.exists(path)) return@runCatching
            val key = name.trim().lowercase()
            val updated = readListFile(path) - key
            if (updated.isEmpty()) fileAccess.delete(path)
            else fileAccess.writeFile(path, updated.sorted().joinToString("\n") + "\n", overwrite = true)
        }.onFailure { FileLogger.w(TAG, "清理清单失败: $path", it) }
    }
}
