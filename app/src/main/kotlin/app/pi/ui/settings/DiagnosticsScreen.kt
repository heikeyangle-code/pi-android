package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pi.runtime.PiPaths
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 → 运行时与诊断 → 导出诊断报告.
 *
 * ## Why this screen is a screen and not a one-tap action
 *
 * The report is the thing being delivered, so it is shown before it is sent: the
 * user can read that the exit code is missing, see which payload failed, and tell
 * whether the stderr is empty — all without leaving the app or opening a file.
 * The two buttons below it are the delivery, and they are separate because they
 * fail differently: Download needs the public-directory write path, the share
 * sheet does not.
 *
 * ## Why it works with a dead engine
 *
 * Nothing here calls the engine. The facts are read from the filesystem, the APK
 * and the platform, and the engine half is a value the owner captured while the
 * process was still reachable ([EngineDiagnostics]). That is deliberate: this is
 * the surface a user reaches for *after* "rpc: engine exited with code 1", and a
 * diagnostic tool that needs a live engine is useless exactly when it is needed.
 *
 * [engine] and [failures] are lambdas rather than values so the screen always
 * reads the newest capture, including one that happened while it was composed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    engine: () -> EngineDiagnostics?,
    failures: () -> List<String>,
) {
    val context = LocalContext.current
    val paths = remember(context) {
        PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
    }
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(true) }

    // One `LaunchedEffect(Unit)`: the report is built once when the screen opens
    // and again only on an explicit refresh. Reading it walks the runtime tree and
    // each packaged payload, which is not something to repeat per frame or per
    // recomposition.
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            DiagnosticsReport.build(context, paths, engine(), failures())
        }
        working = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("诊断报告") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = PiSpacing.screen)
                .padding(bottom = contentPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "这份纯文本汇总了引擎最后一次退出的退出码与已捕获的 stderr、运行时与载荷状态、关键路径、" +
                    "最近的失败，以及设备信息。它不会进入对话上下文；敏感值已做脱敏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSpacing.unit))

            Row(
                horizontalArrangement = Arrangement.spacedBy(PiSpacing.unit),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val text = report
                Button(
                    enabled = text != null && !working,
                    onClick = {
                        val current = report ?: return@Button
                        val name = DiagnosticsExport.fileName()
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                DiagnosticsExport.saveToDownloads(context, current, name)
                            }
                        }
                    },
                ) { Text("保存到 Download") }
                OutlinedButton(
                    enabled = text != null && !working,
                    onClick = {
                        val current = report ?: return@OutlinedButton
                        val name = DiagnosticsExport.fileName()
                        scope.launch {
                            status = withContext(Dispatchers.IO) {
                                DiagnosticsExport.share(context, current, name)
                            }
                        }
                    },
                ) { Text("分享") }
            }

            val note = status
            if (note != null) {
                Spacer(Modifier.height(PiSpacing.unit))
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(PiSpacing.unit))
            Text(
                if (working) "正在收集…" else "报告预览（与导出的文件内容一致）",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                report ?: "",
                style = PiTheme.text.monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}
