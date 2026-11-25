package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GroupSourceEditorSheet(
    original: ProxyGroup?,
    onSave: (String?, ProxyGroup) -> String?,
    onDismiss: () -> Unit,
) {
    var name by remember(original) { mutableStateOf(original?.tag.orEmpty()) }
    var newSource by remember(original) { mutableStateOf(GroupSourceType.LOCAL) }
    val source = original?.sourceType ?: newSource
    var url by remember(original) { mutableStateOf(original?.subscriptionUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(true) }
    GlassBottomSheet(show = visible, backdrop = LocalLayerBackdrop.current,
        title = when {
            original == null -> "新建分组"
            source == GroupSourceType.SUBSCRIPTION -> "订阅设置"
            else -> "重命名分组"
        },
        onDismissRequest = { visible = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = name, onValueChange = { name = it }, label = "配置分组名称", modifier = Modifier.fillMaxWidth())
            Card {
                if (original == null) {
                    OverlayDropdownPreference(title = "来源类型", items = listOf("本地", "订阅"),
                        selectedIndex = if (source == GroupSourceType.LOCAL) 0 else 1,
                        onSelectedIndexChange = { newSource = if (it == 0) GroupSourceType.LOCAL else GroupSourceType.SUBSCRIPTION })
                } else {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("来源类型", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                        Text(if (source == GroupSourceType.LOCAL) "本地" else "订阅",
                            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            if (source == GroupSourceType.SUBSCRIPTION) {
                TextField(value = url, onValueChange = { url = it }, label = "订阅链接", modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(onClick = {
                val trimmedName = name.trim()
                if (trimmedName.isEmpty()) {
                    error = "请输入分组名称"
                    return@Button
                }
                if (source == GroupSourceType.SUBSCRIPTION && url.trim().isEmpty()) {
                    error = "请输入订阅链接"
                    return@Button
                }
                val group = (original ?: ProxyGroup(trimmedName, strategy = GroupStrategy.SELECT))
                    .copy(tag = trimmedName, sourceType = source,
                        subscriptionUrl = if (source == GroupSourceType.SUBSCRIPTION) url.trim() else "")
                error = onSave(original?.tag, group)
                if (error == null) visible = false
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
        }
    }
}
