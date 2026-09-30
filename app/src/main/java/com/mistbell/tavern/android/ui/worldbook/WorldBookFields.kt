package com.mistbell.tavern.android.ui.worldbook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
