package com.contexa.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.contexa.android.ui.theme.disabledContainer
import com.contexa.android.ui.theme.disabledContent

/*
 * 모양은 SEED Design의 부품 규격을 따른다(packages/rootage/components).
 * 버튼 = action-button size=large, 꼬리표 = badge size=large, 선택 칩 = chip size=medium.
 */
private val ButtonShape = RoundedCornerShape(12.dp)
private val ButtonPadding = PaddingValues(horizontal = 20.dp)
private val ButtonHeight = 52.dp

/** 주 버튼(brandSolid): 초록으로 채운다. loading이면 글자 대신 돌아가는 표시를 보여 주고 누를 수 없다. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            // 불러오는 중에는 흐려지지 않고 초록 그대로 둔다.
            disabledContainerColor = if (loading) scheme.primary else scheme.disabledContainer,
            disabledContentColor = if (loading) scheme.onPrimary else scheme.disabledContent,
        ),
        modifier = modifier.heightIn(min = ButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        } else {
            Text(label, style = ButtonLabel())
        }
    }
}

/** 보조 버튼(neutralWeak): 옅은 회색으로 채운다. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            disabledContainerColor = scheme.disabledContainer,
            disabledContentColor = scheme.disabledContent,
        ),
        modifier = modifier.heightIn(min = ButtonHeight),
    ) {
        Text(label, style = ButtonLabel())
    }
}

@Composable
private fun ButtonLabel() = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)

/** 작은 꼬리표(종류·언어·상태 등). */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 칠할 바탕. 없으면 옅은 회색이다. */
    container: Color? = null,
) {
    Surface(
        shape = TagShape,
        color = container ?: MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 꼬리표와 같은 모서리를 쓰는 다른 표시(처리 상태 등)가 가져다 쓴다. */
val TagShape = RoundedCornerShape(6.dp)

/** 여럿 중 하나를 고르는 칩. 고른 것은 짙은 회색으로 채우고, 나머지는 테두리만 그린다. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurface,
            selectedContainerColor = scheme.secondary,
            selectedLabelColor = scheme.onSecondary,
        ),
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        modifier = modifier.heightIn(min = 36.dp),
    )
}
