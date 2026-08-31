package com.mistbell.tavern.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mistbell.tavern.android.data.api.model.Character
import com.mistbell.tavern.android.util.ImageUtils

@Composable
fun CompositeCharacterAvatar(
    characters: List<Character>,
    modifier: Modifier = Modifier,
) {
    val visibleCharacters =
        characters
            .filter { it.id.isNotBlank() || it.name.isNotBlank() }
            .take(4)

    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (visibleCharacters.size) {
            0 ->
                Text(
                    text = "?",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            1 ->
                CharacterAvatarSegment(
                    character = visibleCharacters[0],
                    modifier = Modifier.fillMaxSize(),
                )
            2, 3 ->
                Row(Modifier.fillMaxSize()) {
                    visibleCharacters.forEach { character ->
                        CharacterAvatarSegment(
                            character = character,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize(),
                        )
                    }
                }
            else ->
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        visibleCharacters.take(2).forEach { character ->
                            CharacterAvatarSegment(
                                character = character,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                        }
                    }
                    Row(Modifier.weight(1f)) {
                        visibleCharacters.drop(2).take(2).forEach { character ->
                            CharacterAvatarSegment(
                                character = character,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun CharacterAvatarSegment(
    character: Character,
    modifier: Modifier = Modifier,
) {
    val color = parseCharacterColor(character.color)
    val bitmap =
        remember(character.avatarData) {
            ImageUtils.dataUriToBitmap(character.avatarData)
        }

    Box(
        modifier = modifier.background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = character.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = character.name.take(1).ifBlank { "?" },
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (character.name.length <= 1) 16.sp else 14.sp,
            )
        }
    }
}

private fun parseCharacterColor(colorString: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(colorString.ifBlank { "#6D8DFF" }))
    } catch (_: Exception) {
        Color(0xFF6D8DFF)
    }
}
