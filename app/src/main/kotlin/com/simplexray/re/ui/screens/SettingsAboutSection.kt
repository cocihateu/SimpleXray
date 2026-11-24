package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun SettingsAboutSection(mainViewModel: MainViewModel) {
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()

    SmallTitle(text = stringResource(R.string.about))
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = settingsState.info.appVersion,
            summary = stringResource(R.string.version),
        )
        BasicComponent(
            title = settingsState.info.kernelVersion,
            summary = stringResource(R.string.kernel),
        )
    }
}
