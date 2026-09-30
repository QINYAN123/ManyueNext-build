package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.cloud.CloudFavorite
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudPasswordLoginSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.ui.browse.cloud.CloudFavoritesViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CloudFavoritesScreen(
    state: CloudFavoritesViewModel.State,
    contentPadding: PaddingValues,
    onOpen: (CloudFavorite, CloudFavoritesSource) -> Unit,
    onSettings: (CloudFavoritesSource) -> Unit,
    onLoadMore: (CloudProviderKey) -> Unit,
    onRetry: (CloudProviderKey) -> Unit,
    onLogin: (CloudFavoritesSource, String, CharArray) -> Unit,
    showAdultContent: Boolean,
    onShowAdultContentChange: (Boolean) -> Unit,
) {
    var loginSource by remember { mutableStateOf<CloudFavoritesSource?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LazyColumn(contentPadding = contentPadding) {
        item(key = "adult-filter") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(MR.strings.show_r18_content_session), Modifier.weight(1f))
                Switch(checked = showAdultContent, onCheckedChange = onShowAdultContentChange)
            }
        }
        state.providers.forEach { provider ->
            item(key = "header:${provider.provider.name}") {
                Column(Modifier.fillMaxWidth()) {
                    Text(provider.provider.displayName(), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    when (val status = provider.status) {
                        CloudFavoritesViewModel.Status.Loading -> Text(stringResource(MR.strings.loading), Modifier.padding(16.dp))
                        CloudFavoritesViewModel.Status.Missing -> Text(stringResource(MR.strings.source_not_installed, provider.provider.displayName()), Modifier.padding(16.dp))
                        is CloudFavoritesViewModel.Status.Duplicate -> Text(stringResource(MR.strings.cloud_connector_duplicate), Modifier.padding(16.dp))
                        CloudFavoritesViewModel.Status.LoginRequired,
                        CloudFavoritesViewModel.Status.SessionExpired,
                        -> provider.source?.let { source ->
                            Column(Modifier.padding(16.dp)) {
                                Text(if (status is CloudFavoritesViewModel.Status.SessionExpired) stringResource(MR.strings.cloud_session_expired) else stringResource(MR.strings.cloud_session_required))
                                Row(Modifier.padding(top = 8.dp)) {
                                    if (source is CloudPasswordLoginSource) {
                                        Button(onClick = { loginSource = source }) { Text(stringResource(MR.strings.action_login)) }
                                    }
                                    TextButton(onClick = { onSettings(source) }) { Text(stringResource(MR.strings.action_settings)) }
                                }
                            }
                        }
                        is CloudFavoritesViewModel.Status.Failed -> Column(Modifier.padding(16.dp)) {
                            Text(stringResource(status.failure.messageRes()))
                            Row {
                                TextButton(onClick = { onRetry(provider.provider) }) { Text(stringResource(MR.strings.action_retry)) }
                                provider.source?.takeIf { it is CloudPasswordLoginSource }?.let { source ->
                                    TextButton(onClick = { loginSource = source }) { Text(stringResource(MR.strings.action_login)) }
                                }
                            }
                        }
                        CloudFavoritesViewModel.Status.Loaded -> if (provider.items.isEmpty()) {
                            Text(stringResource(MR.strings.cloud_empty), Modifier.padding(16.dp))
                        }
                    }
                }
            }
            items(provider.items, key = { "${provider.provider.name}:${it.remoteId}" }) { favorite ->
                ListItem(
                    headlineContent = { Text(favorite.title, maxLines = 2) },
                    supportingContent = favorite.author?.let { author -> { Text(author) } },
                    modifier = Modifier.clickable { provider.source?.let { source -> onOpen(favorite, source) } },
                )
            }
            if (provider.items.isNotEmpty() && provider.hasNextPage) {
                item(key = "more:${provider.provider.name}") {
                    Button(onClick = { onLoadMore(provider.provider) }, enabled = !provider.isLoading, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(if (provider.isLoading) stringResource(MR.strings.loading) else stringResource(MR.strings.action_load_more))
                    }
                }
            }
            item(key = "divider:${provider.provider.name}") { HorizontalDivider() }
        }
    }

    loginSource?.let { source ->
        AlertDialog(
            onDismissRequest = { loginSource = null; username = ""; password = "" },
            title = { Text(stringResource(MR.strings.cloud_login_title, source.name)) },
            text = {
                Column {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(MR.strings.cloud_username)) }, singleLine = true)
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(MR.strings.cloud_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                Button(onClick = {
                    onLogin(source, username.trim(), password.toCharArray())
                    loginSource = null
                    username = ""
                    password = ""
                }, enabled = username.isNotBlank() && password.isNotEmpty()) { Text(stringResource(MR.strings.action_login)) }
            },
            dismissButton = { TextButton(onClick = { loginSource = null }) { Text(stringResource(MR.strings.action_cancel)) } },
        )
    }
}

private fun eu.kanade.tachiyomi.source.cloud.CloudFailure.messageRes() = when (this) {
    eu.kanade.tachiyomi.source.cloud.CloudFailure.Authentication -> MR.strings.cloud_session_expired
    eu.kanade.tachiyomi.source.cloud.CloudFailure.RateLimited -> MR.strings.cloud_rate_limited
    eu.kanade.tachiyomi.source.cloud.CloudFailure.Network -> MR.strings.cloud_network_error
    eu.kanade.tachiyomi.source.cloud.CloudFailure.Remote -> MR.strings.cloud_remote_error
    eu.kanade.tachiyomi.source.cloud.CloudFailure.Parsing -> MR.strings.cloud_parsing_error
}

private fun CloudProviderKey.displayName(): String = when (this) {
    CloudProviderKey.COPY_MANGA -> "CopyManga 云收藏"
    CloudProviderKey.JM -> "JM 云收藏"
    CloudProviderKey.PICACG -> "PICACG 云收藏"
}
