/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.novavr.app.R
import org.novavr.app.settings.DisplayMode
import org.novavr.app.ui.theme.NovaColors

@Composable
fun ScreenTitleBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = NovaColors.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = NovaColors.Surface,
            border = BorderStroke(1.dp, NovaColors.Outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = NovaColors.Accent)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = NovaColors.TextPrimary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = NovaColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/**
 * Portrait / Landscape picker. Each option shows a miniature of the virtual
 * screen at the real aspect ratio of this device's display.
 */
@Composable
fun DisplayModeSelector(
    selected: DisplayMode,
    portraitAspect: Float,
    onSelect: (DisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeOption(
            title = "Portrait",
            subtitle = "Phone-like screen",
            icon = R.drawable.ic_portrait,
            previewAspect = portraitAspect,
            selected = selected == DisplayMode.PORTRAIT,
            onClick = { onSelect(DisplayMode.PORTRAIT) },
            modifier = Modifier.weight(1f),
        )
        ModeOption(
            title = "Landscape",
            subtitle = "Wide screen",
            icon = R.drawable.ic_landscape,
            previewAspect = 1f / portraitAspect,
            selected = selected == DisplayMode.LANDSCAPE,
            onClick = { onSelect(DisplayMode.LANDSCAPE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    previewAspect: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) NovaColors.AccentDim.copy(alpha = 0.45f) else NovaColors.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) NovaColors.Accent else NovaColors.Outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Miniature virtual screen at the true aspect ratio.
                Surface(
                    modifier = if (previewAspect < 1f) {
                        Modifier.height(64.dp).aspectRatio(previewAspect)
                    } else {
                        Modifier.width(110.dp).aspectRatio(previewAspect)
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) NovaColors.Accent.copy(alpha = 0.25f) else NovaColors.SurfaceHigh,
                    border = BorderStroke(1.5.dp, if (selected) NovaColors.Accent else NovaColors.Outline),
                ) {}
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) NovaColors.Accent else NovaColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
        }
    }
}

/** Large rounded navigation tile used on the home screen. */
@Composable
fun NavTile(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = NovaColors.Surface,
        border = BorderStroke(1.dp, NovaColors.Outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = NovaColors.Accent)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
        }
    }
}
