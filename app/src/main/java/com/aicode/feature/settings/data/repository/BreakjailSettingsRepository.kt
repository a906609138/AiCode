package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.breakjailDataStore by preferencesDataStore(
    name = "breakjail_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「破甲」置顶提示词开关，默认关闭。
 *
 * 开启后，`~/.aicode/pj.md` 的正文以最高优先级拼在整段系统提示词最前，先于专家块与静态基线；
 * 关闭时不注入。pj.md 不参与片段编号体系，不出现在提示词片段列表中，也不被项目层片段覆盖。
 */
@Singleton
class BreakjailSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("pj_prompt_enabled")
    }

    /** 当前开关流；未设置时回退到 false（默认不注入）。读取异常同样回退 false，不让异常取消收集方。 */
    val enabledFlow: Flow<Boolean> = context.breakjailDataStore.data
        .map { it[ENABLED_KEY] ?: false }
        .catch { emit(false) }

    suspend fun setEnabled(enabled: Boolean) {
        context.breakjailDataStore.edit { it[ENABLED_KEY] = enabled }
    }
}
