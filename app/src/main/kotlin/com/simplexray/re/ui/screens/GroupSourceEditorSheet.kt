package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var source by remember(original) { mutableStateOf(original?.sourceType ?: GroupSourceType.LOCAL) }
    var url by remember(original) { mutableStateOf(original?.subscriptionUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(true) }
    GlassBottomSheet(show = visible, backdrop = LocalLayerBackdrop.current,
        title = if (original == null) "新建分组" else "编辑分组",
        onDismissRequest = { visible = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = name, onValueChange = { name = it }, label = "名称", modifier = Modifier.fillMaxWidth())
            Card {
                OverlayDropdownPreference(title = "组类型", items = listOf("基本", "订阅"),
                    selectedIndex = if (source == GroupSourceType.LOCAL) 0 else 1,
                    onSelectedIndexChange = { source = if (it == 0) GroupSourceType.LOCAL else GroupSourceType.SUBSCRIPTION })
            }
            if (source == GroupSourceType.SUBSCRIPTION) {
                TextField(value = url, onValueChange = { url = it }, label = "订阅链接", modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(onClick = {
                val group = (original ?: ProxyGroup(name, strategy = GroupStrategy.SELECT))
                    .copy(tag = name.trim(), sourceType = source, subscriptionUrl = url.trim())
                error = onSave(original?.tag, group)
                if (error == null) visible = false
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
        }
    }
}
