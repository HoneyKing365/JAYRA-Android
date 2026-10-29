package ai.jayra.companion.ui.tabs

import ai.jayra.companion.model.ReActStage
import ai.jayra.companion.model.TaskLogLine
import ai.jayra.companion.theme.JayraAmberWarning
import ai.jayra.companion.theme.JayraCardSurface
import ai.jayra.companion.theme.JayraCrimsonDanger
import ai.jayra.companion.theme.JayraCyanGlow
import ai.jayra.companion.theme.JayraCyanPrimary
import ai.jayra.companion.theme.JayraEmeraldSuccess
import ai.jayra.companion.theme.JayraMono
import ai.jayra.companion.theme.JayraOledBlack
import ai.jayra.companion.theme.TextMuted
import ai.jayra.companion.ui.components.MarkdownLite
import ai.jayra.companion.viewmodel.CompanionViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun stageColor(s: ReActStage?): Color = when (s) {
    ReActStage.PLANNING -> JayraAmberWarning
    ReActStage.EXECUTING -> JayraCyanPrimary
    ReActStage.VERIFYING -> Color(0xFFB388FF)
    ReActStage.COMPLETE -> JayraEmeraldSuccess
    ReActStage.FAILED -> JayraCrimsonDanger
    else -> TextMuted
}

@Suppress("DEPRECATION")
@Composable
fun TasksTab(vm: CompanionViewModel, modifier: Modifier = Modifier) {
    val stage by vm.taskStage.collectAsStateWithLifecycle()
    val task by vm.activeTask.collectAsStateWithLifecycle()
    val log by vm.taskLog.collectAsStateWithLifecycle()
    val card by vm.flashcard.collectAsStateWithLifecycle()
    var newTask by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val selected = remember(card?.cardId) { mutableStateListOf<Int>() }

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }

    LazyColumn(
        modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = vm::emergencyStop,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JayraCrimsonDanger, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Stop, null)
                Text("  EMERGENCY ABORT", style = MaterialTheme.typography.titleSmall)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(16.dp))
                    .border(1.dp, stageColor(stage).copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AGENT STATE", style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.weight(1f))
                    Text(
                        stage.name, style = MaterialTheme.typography.labelLarge, color = JayraOledBlack,
                        modifier = Modifier.background(stageColor(stage), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                Text(task?.goal ?: "No active task", style = MaterialTheme.typography.titleMedium)
                task?.let {
                    if (it.totalSteps > 0) {
                        LinearProgressIndicator(
                            progress = { (it.stepNumber.toFloat() / it.totalSteps).coerceIn(0f, 1f) },
                            color = stageColor(it.stage), modifier = Modifier.fillMaxWidth()
                        )
                        Text("Step ${it.stepNumber} of ${it.totalSteps}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    } else if (!it.stage.isTerminal) {
                        LinearProgressIndicator(color = stageColor(it.stage), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTask, onValueChange = { newTask = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Give JAYRA a task…", color = TextMuted) }, maxLines = 3
                )
                IconButton(onClick = { vm.triggerTask(newTask); newTask = "" }, enabled = newTask.isNotBlank()) {
                    Icon(Icons.Filled.PlayArrow, "Run task", tint = JayraCyanPrimary)
                }
            }
        }

        item {
            Text("EXECUTION CONSOLE", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            LazyColumn(
                Modifier.fillMaxWidth().height(260.dp).padding(top = 6.dp)
                    .background(JayraOledBlack, RoundedCornerShape(12.dp)).border(1.dp, JayraCyanGlow, RoundedCornerShape(12.dp)).padding(10.dp),
                state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (log.isEmpty()) item { Text("> awaiting agent activity_", style = JayraMono, color = TextMuted) }
                items(log) { l: TaskLogLine -> Text(l.text, style = JayraMono, color = stageColor(l.stage)) }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.fetchSummary() }, modifier = Modifier.weight(1f)) { Text("DAILY PULSE") }
                OutlinedButton(onClick = { vm.generateSummary() }, modifier = Modifier.weight(1f)) { Text("REGENERATE") }
            }
        }

        card?.let { c ->
            item {
                Column(
                    Modifier.fillMaxWidth().background(JayraCardSurface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MarkdownLite(c.markdownCard)
                    if (c.recommendedTodos.isNotEmpty()) {
                        Text("PROMOTE TO TASK", style = MaterialTheme.typography.labelSmall, color = JayraCyanPrimary)
                        c.recommendedTodos.forEachIndexed { i, todo ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = i in selected, onCheckedChange = { if (it) selected.add(i) else selected.remove(i) })
                                Text(todo, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(
                            onClick = { vm.promoteTodos(selected.sorted().map { c.recommendedTodos[it] }); selected.clear() },
                            enabled = selected.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = JayraCyanPrimary, contentColor = JayraOledBlack)
                        ) { Text("RUN SELECTED") }
                    }
                }
            }
        }
        item { Row(Modifier.height(12.dp)) {} }
    }
}
