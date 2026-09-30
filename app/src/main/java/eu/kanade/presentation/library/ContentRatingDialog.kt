package eu.kanade.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.domain.content.model.ContentRatingOverride
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ContentRatingDialog(
    selected: ContentRatingOverride,
    onSelected: (ContentRatingOverride) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.content_rating)) },
        text = {
            Column {
                ContentRatingOverride.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == option,
                            onClick = { onSelected(option) },
                        )
                        Text(
                            text = stringResource(option.label),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

private val ContentRatingOverride.label
    get() = when (this) {
        ContentRatingOverride.AUTO -> MR.strings.content_rating_auto
        ContentRatingOverride.NORMAL -> MR.strings.content_rating_normal
        ContentRatingOverride.ADULT -> MR.strings.content_rating_adult
    }
