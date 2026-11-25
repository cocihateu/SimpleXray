package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.ui.components.CardHeading
import com.simplexray.re.ui.components.DashboardCard
import com.simplexray.re.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WorkspaceConfigScreen(mainViewModel: MainViewModel, paddingValues: PaddingValues) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingGroupTag by remember { mutableStateOf<String?>(null) }
    var addingNodeToGroup by remember { mutableStateOf<String?>(null) }
    var fileImportGroup by rememberSaveable { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.importFromUriToGroup(fileImportGroup, uri)
    }

    LaunchedEffect(Unit) { mainViewModel.configurationImportTarget() }

    LaunchedEffect(mainViewModel.configurationPageAction) {
        val group = workspace.groupByTag(mainViewModel.selectedConfigurationGroupTag)
        when (mainViewModel.configurationPageAction) {
            "search" -> {
                searching = !searching
                if (searching == false) query = ""
            }
            "import-clipboard" -> mainViewModel.importFromClipboardToGroup(mainViewModel.selectedConfigurationGroupTag)
            "import-file" -> {
                fileImportGroup = mainViewModel.configurationImportTarget()
                filePicker.launch(arrayOf("*/*"))
            }
            "add-node" -> addingNodeToGroup = mainViewModel.configurationImportTarget()
            "edit-group" -> editingGroupTag = group?.tag
            "refresh-subscriptions" -> workspace.groups.filter { it.sourceType == GroupSourceType.SUBSCRIPTION }
                .forEach { mainViewModel.refreshSubscriptionGroup(it.tag) }
        }
        mainViewModel.configurationPageAction = null
    }

    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
        .padding(top = paddingValues.calculateTopPadding() + 8.dp)) {
        if (searching) {
            TextField(value = query, onValueChange = { query = it }, label = "搜索当前分组", singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                trailingIcon = {
                    IconButton(onClick = { query = ""; searching = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                })
        }
        if (workspace.groups.isEmpty()) {
            DashboardCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CardHeading(R.drawable.code, "新建分组")
                    Text("本地分组添加或导入节点，订阅分组从链接获取节点",
                        style = MiuixTheme.textStyles.body2, fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(onClick = { mainViewModel.createGroupRequested = true },
                        cornerRadius = 24.dp, colors = ButtonDefaults.buttonColorsPrimary()) { Text("新建分组") }
                }
            }
        } else {
            key(workspace.groups.map { it.tag }) {
                ConfigurationGroupPages(mainViewModel, workspace.groups,
                    PaddingValues(bottom = paddingValues.calculateBottomPadding()), query,
                    onEditGroup = { editingGroupTag = it })
            }
        }
    }

    if (mainViewModel.createGroupRequested) {
        GroupSourceEditorSheet(null, onSave = { originalTag, group ->
            mainViewModel.saveWorkspaceGroup(originalTag, group).also { error ->
                if (error == null) mainViewModel.selectConfigurationGroup(group.tag)
            }
        }, onDismiss = { mainViewModel.createGroupRequested = false })
    }
    editingGroupTag?.let(workspace::groupByTag)?.let { group ->
        GroupSourceEditorSheet(group, onSave = mainViewModel::saveWorkspaceGroup,
            onDismiss = { editingGroupTag = null })
    }
    addingNodeToGroup?.let { groupTag ->
        NodeConfigSheet(null,
            onSave = { raw, _, _, _ -> mainViewModel.addNodeToGroup(groupTag, raw) },
            onDismiss = { addingNodeToGroup = null })
    }
}

@Composable
private fun ConfigurationGroupPages(
    mainViewModel: MainViewModel,
    groups: List<ProxyGroup>,
    paddingValues: PaddingValues,
    query: String,
    onEditGroup: (String) -> Unit,
) {
    val initialIndex = groups.indexOfFirst { it.tag == mainViewModel.selectedConfigurationGroupTag }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { groups.size })
    val tabState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        pagerState.scrollToPage(initialIndex)
        snapshotFlow { pagerState.settledPage }.collect { index ->
            mainViewModel.selectConfigurationGroup(groups[index].tag)
        }
    }
    LaunchedEffect(mainViewModel.selectedConfigurationGroupTag) {
        val index = groups.indexOfFirst { it.tag == mainViewModel.selectedConfigurationGroupTag }
        if (index >= 0 && index != pagerState.currentPage) pagerState.scrollToPage(index)
    }
    LaunchedEffect(pagerState.currentPage) { tabState.animateScrollToItem(pagerState.currentPage) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(state = tabState, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(groups, key = { _, group -> group.tag }) { index, group ->
                val active = pagerState.currentPage == index
                Column(Modifier.widthIn(min = 72.dp, max = 180.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.background)
                    .semantics { selected = active }
                    .combinedClickable(role = Role.Tab,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        onLongClick = { onEditGroup(group.tag) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(group.tag, style = MiuixTheme.textStyles.body1, fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (active) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f), key = { groups[it].tag }) { index ->
            GroupNodeConfigurationContent(mainViewModel = mainViewModel, groupTag = groups[index].tag,
                paddingValues = paddingValues,
                searchQuery = query)
        }
    }
}
