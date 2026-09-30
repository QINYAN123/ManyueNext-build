package eu.kanade.tachiyomi.ui.browse.cloud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.CloudFavoritesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.cloudFavoritesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val targetMissingText = stringResource(MR.strings.cloud_target_missing)
    val openFailedText = stringResource(MR.strings.cloud_open_error)
    val viewModel = metroViewModel<CloudFavoritesViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    return TabContent(
        titleRes = MR.strings.cloud_favorites,
        actions = listOf(AppBar.Action(
            title = stringResource(MR.strings.action_webview_refresh),
            icon = MaterialSymbols.Rounded.Refresh,
            onClick = viewModel::refresh,
        )),
        content = { contentPadding, snackbarHostState ->
            CloudFavoritesScreen(state, contentPadding, viewModel::open, viewModel::openSettings, viewModel::loadMore, viewModel::retry, viewModel::login, viewModel.showAdultContent.collectAsStateWithLifecycle().value, viewModel::setAdultContentShown)
            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is CloudFavoritesViewModel.Event.Open -> navigator.push(MangaScreen(event.mangaId))
                        is CloudFavoritesViewModel.Event.Settings -> navigator.push(SourcePreferencesScreen(event.sourceId))
                        is CloudFavoritesViewModel.Event.TargetMissing -> snackbarHostState.showSnackbar("$targetMissingText: ${event.displayName}")
                        CloudFavoritesViewModel.Event.OpenFailed -> snackbarHostState.showSnackbar(openFailedText)
                    }
                }
            }
        },
    )
}
