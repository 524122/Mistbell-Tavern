package com.mistbell.tavern.android.ui.regex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.regex.CardRegexEngine
import com.mistbell.tavern.android.data.regex.GlobalRegexStore
import com.mistbell.tavern.android.data.repository.CharacterRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 角色卡正则概要：hub 页聚合展示用（规则数实时解析卡内 extensions） */
data class CharacterRegexSummary(
    val characterId: String,
    val name: String,
    val ruleCount: Int,
)

data class RegexHubUiState(
    val globalCount: Int = 0,
    val characters: List<CharacterRegexSummary> = emptyList(),
    /** 卡级正则停用名单（默认空=全部允许；对标酒馆按角色放行卡内脚本的开关） */
    val disabledCharacterIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
)

/** 「正则」Tab 宿主：全局规则概览 + 各角色卡内嵌正则聚合 */
class RegexHubViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as TavernApplication).container.database
    private val characterRepo = CharacterRepository(application)

    val state: StateFlow<RegexHubUiState> =
        combine(
            db.globalRegexDao().observeAll(),
            characterRepo.observeCharacters(),
            db.settingsDao().observeValue(GlobalRegexStore.KEY_CARD_REGEX_DISABLED),
        ) { rules, characters, disabledRaw ->
            RegexHubUiState(
                globalCount = rules.size,
                characters =
                    characters.map { character ->
                        CharacterRegexSummary(
                            characterId = character.id,
                            name = character.name,
                            ruleCount = CardRegexEngine.parse(character.data?.extensions).size,
                        )
                    },
                disabledCharacterIds = GlobalRegexStore.parseDisabledIds(disabledRaw),
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RegexHubUiState())

    /** 停用仅拦截卡级脚本；全局正则不受影响 */
    fun setCharacterRegexEnabled(
        characterId: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val updated =
                if (enabled) {
                    state.value.disabledCharacterIds - characterId
                } else {
                    state.value.disabledCharacterIds + characterId
                }
            db.settingsDao().upsert(
                SettingsEntity(GlobalRegexStore.KEY_CARD_REGEX_DISABLED, GlobalRegexStore.formatDisabledIds(updated)),
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
