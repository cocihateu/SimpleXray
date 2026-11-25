package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplexray.re.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop

@Composable
fun EditableListItemWithMiuixBottomSheet(
    headline: String,
    currentValue: String,
    onValueConfirmed: (String) -> Unit,
    label: String,
    supportingText: String? = null,
    customSummary: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }
    val backdrop = LocalLayerBackdrop.current

    if (showSheet) {
        GlassBottomSheet(
            title = headline,
            show = true,
            backdrop = backdrop,
            onDismissRequest = { showSheet = false },
            startAction = {
                IconButton(onClick = { showSheet = false }) {
                    Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
                }
            },
            endAction = {
                IconButton(onClick = {
                    onValueConfirmed(tempValue)
                    showSheet = false
                }) {
                    Icon(imageVector = MiuixIcons.Ok, contentDescription = stringResource(R.string.confirm))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                TextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = label,
                    keyboardOptions = keyboardOptions,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!supportingText.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = supportingText,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }

    ArrowPreference(
        title = headline,
        summary = customSummary,
        endActions = {
            if (customSummary == null) {
                Text(
                    text = currentValue,
                    color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        },
        onClick = {
            tempValue = currentValue
            showSheet = true
        },
        enabled = enabled
    )
}
