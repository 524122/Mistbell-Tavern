package com.mistbell.tavern.android.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TypingIndicator(characterName: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val cardShape = RoundedCornerShape(16.dp)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(min = 96.dp, max = 520.dp)
                    .shadow(
                        elevation = 1.dp,
                        shape = cardShape,
                        ambientColor = Color.Black.copy(alpha = 0.04f),
                        spotColor = Color.Black.copy(alpha = 0.04f),
                    )
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { index ->
                    val delay = index * 200
                    val bounce by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 0f,
                        animationSpec =
                            infiniteRepeatable(
                                animation =
                                    keyframes {
                                        durationMillis = 1400
                                        0f at delay
                                        (-5f) at delay + 200
                                        0f at delay + 400
                                    },
                                repeatMode = RepeatMode.Restart,
                            ),
                        label = "dot$index",
                    )
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 0.4f,
                        animationSpec =
                            infiniteRepeatable(
                                animation =
                                    keyframes {
                                        durationMillis = 1400
                                        0.4f at delay
                                        1f at delay + 200
                                        0.4f at delay + 400
                                    },
                                repeatMode = RepeatMode.Restart,
                            ),
                        label = "alpha$index",
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .offset(y = bounce.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}
