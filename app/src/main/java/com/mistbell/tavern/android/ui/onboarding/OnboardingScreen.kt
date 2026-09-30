package com.mistbell.tavern.android.ui.onboarding

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.repository.CharacterRepository
import com.mistbell.tavern.android.util.CharacterImportResult
import com.mistbell.tavern.android.util.CharacterImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 引导完成标记：写入 settings KV，AppNavigation 据此决定 startDestination
const val ONBOARDING_DONE_KEY = "onboarding_done"

/** 内置示例角色（assets/sample_characters/ 下的 v2 卡片，随 APK 分发） */
data class SampleCharacter(
    val assetName: String,
    val displayName: String,
    val blurb: String,
)

val sampleCharacters =
    listOf(
        SampleCharacter("aria.json", "阿莉雅", "雾铃镇「夜航灯」酒馆的老板娘，记性极好，认得镇上每一张脸"),
        SampleCharacter("kael.json", "凯尔", "星痕航道的独行者，话少，但每一句都算数"),
    )

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val db get() = TavernApplication.instance.container.database
    private val characterRepo = CharacterRepository(application)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _importedName = MutableStateFlow<String?>(null)
    val importedName: StateFlow<String?> = _importedName.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 导入 assets 内置示例角色（落库 + 可能的世界书）。广捕异常：任何失败都转化为界面错误提示，不崩溃 */
    @Suppress("TooGenericExceptionCaught")
    fun importSample(sample: SampleCharacter) {
        viewModelScope.launch {
            try {
                _busy.value = true
                val json =
                    getApplication<Application>()
                        .assets.open("sample_characters/${sample.assetName}")
                        .bufferedReader()
                        .use { it.readText() }
                val result = com.mistbell.tavern.android.util.CardParser.parse(json)
                if (result == null) {
                    _error.value = "示例角色解析失败，请跳过此步骤"
                    return@launch
                }
                persistImport(result)
                _importedName.value = result.character.name
            } catch (e: Exception) {
                android.util.Log.e("Onboarding", "Sample import failed", e)
                _error.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 导入用户自选的角色卡文件（json / png 埋卡）。广捕异常同理 */
    @Suppress("TooGenericExceptionCaught")
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _busy.value = true
                val context = getApplication<Application>()
                val result =
                    CharacterImporter.importFromPng(context, uri)
                        ?: CharacterImporter.importFromJson(context, uri)
                if (result == null) {
                    _error.value = "无法解析所选文件：请确认是角色卡 JSON 或 PNG 埋卡"
                    return@launch
                }
                persistImport(result)
                _importedName.value = result.character.name
            } catch (e: Exception) {
                android.util.Log.e("Onboarding", "Card import failed", e)
                _error.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    // 与 CharacterListViewModel 的导入落库路径保持一致：世界书先行、角色随后（不打印卡内容，隐私）
    private suspend fun persistImport(result: CharacterImportResult) {
        if (result.worldBook != null) {
            db.worldBookDao().upsertBook(result.worldBook)
            if (result.worldBookEntries.isNotEmpty()) {
                db.worldBookDao().upsertEntries(result.worldBookEntries)
            }
        }
        characterRepo.createCharacter(result.character.toDomain())
        android.util.Log.d("Onboarding", "Imported character: ${result.character.name}")
    }

    /** 引导完成：置标记 + 回调导航（由界面层在导航成功后语义闭合） */
    fun markDone() {
        viewModelScope.launch {
            db.settingsDao().upsert(SettingsEntity(ONBOARDING_DONE_KEY, "1"))
        }
    }
}

/**
 * 首启引导（v0.8）：欢迎 → 准备角色（示例卡/导入卡/跳过）→ 配置 API → 进入应用。
 * 三步均为软性步骤——任何一步可跳过，先让用户体验应用本体。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    androidx.compose.ui.platform.LocalContext.current.applicationContext as Application,
                ),
        ),
) {
    val busy by viewModel.busy.collectAsState()
    var step by remember { mutableStateOf(0) }

    val cardPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.importFromUri(it) }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        when (step) {
            0 -> WelcomeStep(onStart = { step = 1 })
            1 ->
                CharacterStep(
                    viewModel = viewModel,
                    onPickCardFile = { cardPicker.launch("*/*") },
                    onNext = { step = 2 },
                )
            else ->
                ApiStep(
                    busy = busy,
                    onFinish = {
                        viewModel.markDone()
                        onFinished()
                    },
                )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("欢迎来到 Mistbell Tavern", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "口袋里的 AI 角色酒馆：自定义角色、长期记忆、群聊。所有数据只存在你的设备上。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("开始")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun CharacterStep(
    viewModel: OnboardingViewModel,
    onPickCardFile: () -> Unit,
    onNext: () -> Unit,
) {
    val busy by viewModel.busy.collectAsState()
    val importedName by viewModel.importedName.collectAsState()
    val error by viewModel.error.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepTitle("第 1 步 · 准备一位角色")
        Text(
            "导入 SillyTavern 生态的角色卡（JSON 或 PNG 埋卡），或从内置示例开始。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        importedName?.let {
            Text("已导入：$it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        sampleCharacters.forEach { sample ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        sample.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        sample.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { viewModel.importSample(sample) }, enabled = !busy) {
                        Text("导入这位角色")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPickCardFile, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("导入我的角色卡")
            }
            TextButton(onClick = onNext) { Text("跳过") }
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(if (importedName != null) "下一步" else "暂不导入，下一步")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiStep(
    busy: Boolean,
    onFinish: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepTitle("第 2 步 · 连接 AI（可稍后配置）")
        Text(
            "Mistbell 通过 OpenAI 兼容接口对话。你需要：\n" +
                "1. 任选一家 LLM 服务商（官方 OpenAI、DeepSeek、月之暗面或任意兼容网关）获取 API Key；\n" +
                "2. 进入应用后，在底部「设置」页的 API 卡片区填入地址、Key 与模型名；\n" +
                "3. 回到角色页，开始第一场对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onFinish, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("进入应用")
        }
        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text("稍后配置，先进入应用")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}
