package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.rootToolDataStore by preferencesDataStore(
    name = "root_tool_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「Root 工具」开关，**默认关闭**。
 *
 * Root 以宿主 UID 0 执行命令，能力最强、风险最高（可写系统分区、读其它应用私有目录），
 * 因此默认关闭；只有检测到设备已 root 且用户显式开启后，AI 才可调用 `Root` 工具。
 *
 * 与「禁用安全拦截」（[ToolSafetySettingsRepository]）的职责不同：那个决定 AUTO 模式是否
 * 自动放行高危命令，本开关决定 Root 工具是否可用。两者都开启时才真正放开。
 */
@Singleton
class RootToolSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("root_tool_enabled")
    }

    /** 当前开关流；未设置时回退到 false（默认关闭）。 */
    val enabledFlow: Flow<Boolean> =
        context.rootToolDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 一次性读取当前值（工具执行前判定用）。 */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.rootToolDataStore.edit { it[ENABLED_KEY] = enabled }
    }
}
