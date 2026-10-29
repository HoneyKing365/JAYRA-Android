package ai.jayra.companion.ui.components

import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraNavySurface
import ai.jayra.companion.theme.TextMuted
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Suppress("DEPRECATION")
enum class JayraTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    CHAT("Chat", Icons.Filled.Chat),
    TASKS("Tasks", Icons.Filled.Bolt),
    SETTINGS("Settings", Icons.Filled.Settings)
}

@Composable
fun BottomNavBar(selected: Int, onSelect: (Int) -> Unit, taskBadge: Boolean, modifier: Modifier = Modifier) {
    NavigationBar(modifier, containerColor = JayraNavySurface) {
        JayraTab.entries.forEachIndexed { i, tab ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = {
                    BadgedBox(badge = { if (tab == JayraTab.TASKS && taskBadge) Badge(containerColor = JayraCrimsonDanger) }) {
                        Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(24.dp))
                    }
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = JayraCyanPrimary,
                    selectedTextColor = JayraCyanPrimary,
                    indicatorColor = JayraCyanGlow,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
            )
        }
    }
}
