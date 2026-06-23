package com.mistbell.tavern.android.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mistbell.tavern.android.ui.theme.CharColors

@Composable
fun ColorPickerGrid(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorMap = CharColors.associateBy { colorToHex(it) }

    Column(modifier = modifier) {
        for (row in CharColors.chunked(4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (color in row) {
                    val hex = colorToHex(color)
                    val isSelected = hex.equals(selectedColor, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .clickable { onColorSelected(hex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "已选择",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Fill empty spaces in the last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }
            if (row != CharColors.chunked(4).last()) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

fun colorToHex(color: Color): String {
    val red = (color.red * 255).toInt()
    val green = (color.green * 255).toInt()
    val blue = (color.blue * 255).toInt()
    return "#%02X%02X%02X".format(red, green, blue)
}
