package com.mistbell.tavern.android.ui.worldbook

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.ui.components.FormTextField

/**
 * 插入位置下拉（酒馆式 9 选项，顺序与文案对齐酒馆）：
 * 6 锚点（角色定义/示例消息/作者注释 各前后）+ [系统/用户/AI] 插入深度 @D 三档。
 * 选中 @D 档时由调用方补显深度数字框；档位与存储字段的互转见
 * [placementSelectedKey] / [placementFromSelection]（纯函数，有单测）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldBookPlacementField(
    insertPosition: String,
    depth: Int,
    depthRole: String,
    onSelect: (insertPosition: String, depth: Int, depthRole: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = placementOptions()
    val selectedKey = placementSelectedKey(insertPosition, depth, depthRole)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selectedKey }?.second ?: "角色定义前",
            onValueChange = {},
            readOnly = true,
            label = { Text("插入位置") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(8.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    val (pos, d, role) = placementFromSelection(key, insertPosition, depth, depthRole)
                    onSelect(pos, d, role)
                    expanded = false
                })
            }
        }
    }
}

/** 次关键词逻辑 4 档（酒馆 world_info_logic）：（枚举值， 显示文案），顺序与酒馆一致 */
@Suppress("MagicNumber") // 枚举值即语义本体（与迁移版本号同型）
fun selectiveLogicOptions(): List<Pair<Int, String>> =
    listOf(
        0 to "AND ANY（任一次词命中）",
        1 to "NOT ALL（任一次词未命中）",
        2 to "NOT ANY（次词全部未命中）",
        3 to "AND ALL（次词全部命中）",
    )

/** 次关键词逻辑下拉（仅当填了次关键词时有意义；值即酒馆 selectiveLogic 枚举） */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名（同 WorldBookPlacementField）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldBookLogicField(
    selectiveLogic: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = selectiveLogicOptions()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selectiveLogic }?.second ?: options.first().second,
            onValueChange = {},
            readOnly = true,
            label = { Text("次关键词逻辑") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            shape = RoundedCornerShape(8.dp),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelect(value)
                    expanded = false
                })
            }
        }
    }
}

/** 角色过滤（酒馆 characterFilter）：名字/标签任一命中即限定生效；exclude=反转为黑名单 */
@Suppress("FunctionNaming") // Compose 组件按官方约定 PascalCase 命名
@Composable
internal fun EntryCharacterFilterFields(
    entryForm: WorldBookEntryForm,
    viewModel: WorldBookEditorViewModel,
) {
    Text(
        "角色过滤（留空 = 全部角色生效）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FormTextField(
        value = entryForm.characterFilterNames,
        onValueChange = { viewModel.updateEntryForm { copy(characterFilterNames = it) } },
        label = "角色名（逗号分隔）",
        placeholder = "仅这些角色的会话生效",
    )
    FormTextField(
        value = entryForm.characterFilterTags,
        onValueChange = { viewModel.updateEntryForm { copy(characterFilterTags = it) } },
        label = "角色标签（逗号分隔）",
        placeholder = "或按标签限定",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = entryForm.characterFilterExclude,
            onCheckedChange = { viewModel.updateEntryForm { copy(characterFilterExclude = it) } },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("排除以上（黑名单）", style = MaterialTheme.typography.bodyMedium)
    }
}
