package com.ariateam.aria.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariateam.aria.R

val FloatingTabBarHeight = 60.dp
val FloatingTabBarBottomMargin = 12.dp

@Composable
fun calculateFloatingNavSafeBottomPadding(tabBarHeight: Dp = FloatingTabBarHeight): Dp {
    val navBars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return tabBarHeight + FloatingTabBarBottomMargin + navBars + 8.dp
}

@Composable
fun FloatingTabBar(
    selectedTab: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: ((Dp) -> Unit)? = null,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        val heightDp = with(density) { size.height.toDp() }
                        onHeightChanged?.invoke(heightDp)
                    }
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .selectableGroup()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(R.string.home_tab, R.string.settings_tab).forEachIndexed { index, labelRes ->
                    val selected = selectedTab == index
                    val tabText = stringResource(labelRes)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else Color.Transparent
                            )
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelected(index) },
                            )
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tabText,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}
