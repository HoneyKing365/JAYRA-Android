package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class QuickAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun QuickActionChips(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        items(actions) { a ->
            Row(
                Modifier
                    .background(JayraCardSurface, RoundedCornerShape(14.dp))
                    .border(1.dp, JayraCyanGlow, RoundedCornerShape(14.dp))
                    .clickable(onClick = a.onClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(a.icon, null, tint = JayraCyanPrimary, modifier = Modifier.size(18.dp))
                Text(a.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
