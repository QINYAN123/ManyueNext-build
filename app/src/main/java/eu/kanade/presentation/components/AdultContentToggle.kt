package eu.kanade.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AdultContentToggle(
    expanded: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconToggleButton(
        checked = expanded,
        onCheckedChange = { onClick() },
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}
