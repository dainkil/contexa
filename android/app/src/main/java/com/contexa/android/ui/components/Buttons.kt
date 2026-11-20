package com.contexa.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ButtonPadding = PaddingValues(horizontal = 24.dp)

/** 주 버튼: 초록으로 채운 알약. loading이면 글자 대신 돌아가는 표시를 보여 주고 누를 수 없다. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        contentPadding = ButtonPadding,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}

/** 보조 버튼: 2dp 테두리만 있는 알약. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ink = MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = ButtonPadding,
        border = BorderStroke(2.dp, if (enabled) ink else ink.copy(alpha = 0.12f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ink),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Text(label)
    }
}

/** 작은 꼬리표(종류·언어·"나만 보기" 등). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.Transparent,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
