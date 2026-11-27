package com.mistbell.tavern.android.ui.onboarding

import android.app.Application
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mistbell.tavern.android.TavernApplication
import com.mistbell.tavern.android.data.local.entity.SettingsEntity
import com.mistbell.tavern.android.data.model.UiMode
import com.mistbell.tavern.android.data.repository.SettingsRepository
import com.mistbell.tavern.android.util.BuiltInCharacters
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 引导完成标记：写入 settings KV，AppNavigation 据此决定 startDestination
const val ONBOARDING_DONE_KEY = "onboarding_done"

/**
 * 首启三屏（P3 第 4 项，docs/ROADMAP.md「P3 门面」）：欢迎 → 选一位角色 → 连接 AI。
 * 步骤推进的纯规则（零依赖，可 JVM 单测）：任何一步都是软性的——每屏的主按钮都能前进，
 * 选角色可以不选直接过（「稍后再选」），连接 AI 可以稍后配置。
 */
object OnboardingFlow {
    const val STEP_WELCOME = 0
    const val STEP_CHARACTER = 1
    const val STEP_API = 2

    /** 下一步（最后一屏不再推进——由「进入应用」结束引导，导航层弹出引导栈） */
    fun next(step: Int): Int = minOf(step + 1, STEP_API)

    /** 选角色步的安装决策：选了角色才落库；没选（「稍后再选」）不写任何数据 */
    fun shouldInstallBuiltIns(selectedId: String?): Boolean = selectedId != null
}

/**
 * 引导完成时的 settings 落库内容（纯函数，可 JVM 单测）。
 *
 * 全新安装默认简洁（P3 第 2 项，docs/UI_MODE.md §5）：引导未完成的设备被判定为全新安装，
 * 引导完成即把 `ui_mode = "simple"` 落库——是显式写值，不改 `UiMode.fromSetting` 的缺省
 * （执行闸门缺省高级不变）。主导航层的升级首启判定（UiModePromptViewModel）据此把
 * 全新安装识别为「保持 simple」，不再弹模式选择；升级用户（引导标记早已存在）不受影响。
 */
object OnboardingCompletion {
    /** 键值对列表：引导完成标记 + 简洁模式缺省（写入者只有 OnboardingViewModel.markDone） */
    fun settingsWrites(): List<Pair<String, String>> =
        listOf(
            ONBOARDING_DONE_KEY to "1",
            SettingsRepository.KEY_UI_MODE to UiMode.SIMPLE.storedValue,
        )
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val db get() = TavernApplication.instance.container.database

    /** 首启可选的全部内置角色（与角色 Tab 的「内置角色」入口同一份目录） */
    val builtIns: List<BuiltInCharacters.Definition> = BuiltInCharacters.all

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    /** 点选一位内置角色（再点取消；单选） */
    fun select(id: String) {
        _selectedId.value = if (_selectedId.value == id) null else id
    }

    /**
     * 选角色步完成：选了角色 → 全部内置角色落库（[BuiltInCharacters.install] 按 id 幂等，
     * 选中的那位因此落库即可以直接开始聊天）；没选 → 不写任何数据（「稍后再选」），
     * 角色 Tab 的「内置角色」入口随时可补。
     *
     * 几 KB 资产的解析落库在后台跑完即可，不阻塞进入下一屏；失败只记日志（资产可解析性
     * 由 SampleCharactersTest 守住，走到这里基本不可能失败）。
     *
     * 写入包在 [NonCancellable] 里：用户选完角色可能马上点「稍后配置，先进入应用」，导航会把
     * 引导页弹掉、本 ViewModel 被清掉——不包的话协程会在写到一半时被取消，四个角色只落了一两个，
     * 而引导标记已置上、不会再出第二次。[markDone] 会先等这个任务结束再写标记。
     */
    @Suppress("TooGenericExceptionCaught") // 广捕异常：内置安装失败只记日志不崩溃（资产可解析性由单测守住）
    fun completeCharacterStep() {
        if (!OnboardingFlow.shouldInstallBuiltIns(_selectedId.value)) return
        builtInInstallJob =
            viewModelScope.launch {
                withContext(NonCancellable) {
                    try {
                        BuiltInCharacters.install(db, BuiltInCharacters.all) { assetName ->
                            getApplication<Application>()
                                .assets
                                .open(BuiltInCharacters.ASSET_DIR + assetName)
                                .bufferedReader()
                                .use { it.readText() }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Onboarding", "Built-in character install failed", e)
                    }
                }
            }
    }

    /** 选角色步的落库任务；[markDone] 先等它结束再写引导标记 */
    private var builtInInstallJob: Job? = null

    /**
     * 引导完成：置标记 + 写简洁缺省（内容见 [OnboardingCompletion]）；导航由界面层回调。
     * 同样包在 [NonCancellable] 里，并且先等内置角色落库结束——保证「引导已完成」成立时，
     * 选角色那一步承诺写入的数据已经全部写完。
     */
    fun markDone() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                builtInInstallJob?.join()
                OnboardingCompletion.settingsWrites().forEach { (key, value) ->
                    db.settingsDao().upsert(SettingsEntity(key, value))
                }
            }
        }
    }
}

/**
 * 首启引导（P3 第 4 项）：欢迎 → 选一位角色 → 连接 AI。
 * 三屏均为软性步骤——任何一步可跳过，先让用户体验应用本体；
 * 酒馆导入退为选角色页的次要按钮（「导入已有角色」，进入「导入」页）。
 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    /** 次要按钮「导入已有角色」的去向：「导入」页（P3 第 3 项），老卡 / 数据文件夹 / 聊天记录都在那里 */
    onOpenImport: () -> Unit = {},
    viewModel: OnboardingViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
                    androidx.compose.ui.platform.LocalContext.current.applicationContext as Application,
                ),
        ),
) {
    val selectedId by viewModel.selectedId.collectAsState()
    // 屏序必须可保存：选角色屏的「导入已有角色」会跳去「导入」页，返回时本页的组合作废重建——
    // 用 remember 的话屏序丢回欢迎屏（装机实测复现），rememberSaveable 才能回到选角色屏
    var step by rememberSaveable { mutableStateOf(OnboardingFlow.STEP_WELCOME) }

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
            OnboardingFlow.STEP_WELCOME -> WelcomeStep(onStart = { step = OnboardingFlow.next(step) })
            OnboardingFlow.STEP_CHARACTER ->
                CharacterStep(
                    builtIns = viewModel.builtIns,
                    selectedId = selectedId,
                    onSelect = viewModel::select,
                    onOpenImport = onOpenImport,
                    onNext = {
                        viewModel.completeCharacterStep()
                        step = OnboardingFlow.next(step)
                    },
                )
            else ->
                ApiStep(
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
            "在这里和 AI 角色聊天：他们按各自的性格与你说话，也会记住你们聊过的事。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        WelcomePoint("选一位角色，随时开始对话")
        WelcomePoint("角色会记住你们聊过的细节，越聊越熟")
        WelcomePoint("所有数据只保存在这台设备上，没有账号，也不会上传")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("开始")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun WelcomePoint(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("·", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class) // Card(onClick) 为 M3 实验 API
@Suppress("FunctionNaming")
@Composable
private fun CharacterStep(
    builtIns: List<BuiltInCharacters.Definition>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    /** 次要按钮「导入已有角色」：进入「导入」页（含老卡 / 数据文件夹 / 世界书 / 聊天记录 / 备份） */
    onOpenImport: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepTitle("选一位角色")
        Text(
            "点选一位作为你的第一位角色；也可以先不选，之后再挑。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        builtIns.forEach { def ->
            BuiltInCharacterRow(
                def = def,
                selected = def.id == selectedId,
                onClick = { onSelect(def.id) },
            )
        }

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(if (selectedId != null) "就这样，下一步" else "稍后再选")
        }
        // 次要入口（P3 第 4 项）：酒馆导入退为中性措辞的次要按钮，用户主动进入「导入」页后才谈老格式
        TextButton(onClick = onOpenImport, modifier = Modifier.fillMaxWidth()) {
            Text("导入已有角色")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // Card(onClick) 为 M3 实验 API
@Suppress("FunctionNaming")
@Composable
private fun BuiltInCharacterRow(
    def: BuiltInCharacters.Definition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已选",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(def.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    def.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ApiStep(onFinish: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepTitle("连接 AI（可稍后配置）")
        Text(
            "Mistbell 通过 OpenAI 兼容接口对话。你需要：\n" +
                "1. 任选一家 LLM 服务商（官方 OpenAI、DeepSeek、月之暗面或任意兼容网关）获取 API Key；\n" +
                "2. 进入应用后，在底部「设置」页的 API 卡片区填入地址、Key 与模型名；\n" +
                "3. 回到角色页，开始第一场对话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
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
