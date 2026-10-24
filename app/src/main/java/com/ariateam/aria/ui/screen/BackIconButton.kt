package com.ariateam.aria.ui.screen

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ariateam.aria.R

/**
 * Shared accessible top-left back icon button using the chevron drawable asset.
 * Enforces a minimum 48dp touch target, accessible Korean content description,
 * appropriate theme tint, and optional test tagging.
 */
@Composable
fun BackIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.debug_back_to_settings),
    testTag: String? = null,
) {
    val appliedModifier = if (testTag != null) {
        modifier.testTag(testTag)
    } else {
        modifier
    }
    IconButton(
        onClick = onClick,
        modifier = appliedModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_left),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
