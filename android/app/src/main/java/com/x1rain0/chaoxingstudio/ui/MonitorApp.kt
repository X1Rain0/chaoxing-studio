package com.x1rain0.chaoxingstudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.x1rain0.chaoxingstudio.MonitorViewModel
import com.x1rain0.chaoxingstudio.data.ActivityCard
import com.x1rain0.chaoxingstudio.data.ActivitySignFlow
import com.x1rain0.chaoxingstudio.data.CourseChoice
import com.x1rain0.chaoxingstudio.data.MonitorMode
import com.x1rain0.chaoxingstudio.data.MonitorUiState
import com.x1rain0.chaoxingstudio.data.activitySignFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class Destination(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Filled.Dashboard),
    COURSES("课程", Icons.Outlined.MenuBook),
    RECORDS("记录", Icons.Outlined.History),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonitorApp(
    viewModel: MonitorViewModel,
    debugShowMapPicker: Boolean = false,
    debugSignPreviewKind: Int? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var destination by rememberSaveable { mutableStateOf(Destination.OVERVIEW) }
    var backgroundStartPending by remember { mutableStateOf(false) }
    var debugMapOpen by rememberSaveable { mutableStateOf(debugShowMapPicker) }
    var signActivity by remember { mutableStateOf<ActivityCard?>(null) }
    val signConfig = viewModel.signInParameters()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (backgroundStartPending) {
            if (granted) viewModel.startMonitoring()
            else viewModel.reportError("未授予通知权限，后台新活动将无法提醒")
            backgroundStartPending = false
        }
    }

    LaunchedEffect(state.loggedIn, debugSignPreviewKind) {
        if (!state.loggedIn) {
            destination = Destination.OVERVIEW
            signActivity = null
        } else if (debugSignPreviewKind != null) {
            val flow = activitySignFlow(debugSignPreviewKind)
            signActivity = ActivityCard(
                activeId = "debug-preview-$debugSignPreviewKind",
                name = flow.actionLabel,
                kind = debugSignPreviewKind,
                kindName = flow.actionLabel,
                courseTitle = "仅本机预览，不会提交签到",
                startedAtMillis = System.currentTimeMillis(),
                rawJson = "{}",
                signed = false,
            )
        }
    }
    BackHandler(enabled = state.loggedIn && destination != Destination.OVERVIEW) {
        destination = Destination.OVERVIEW
    }

    val onStartMonitoring: () -> Unit = {
        val needsNotificationPermission = state.mode == MonitorMode.BACKGROUND &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            backgroundStartPending = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startMonitoring()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (state.loggedIn) {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("学习通签到工坊", style = MaterialTheme.typography.titleMedium)
                                if (state.accountName.isNotBlank()) {
                                    Text(state.accountName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { destination = Destination.SETTINGS }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "设置")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                }
            },
            bottomBar = {
                if (state.loggedIn && !expanded) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.loggedIn && state.error.isNotBlank()) {
                    ErrorBanner(state.error, viewModel::dismissError)
                }
                if (state.loggedIn && state.signNotice.isNotBlank()) {
                    SignNoticeBanner(state.signNotice, viewModel::dismissSignNotice)
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (state.loggedIn && expanded) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            Destination.entries.forEach { item ->
                                NavigationRailItem(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.loggedIn) {
                            when (destination) {
                                Destination.OVERVIEW -> OverviewScreen(
                                    state = state,
                                    onStart = onStartMonitoring,
                                    onStop = viewModel::stopMonitoring,
                                    onAutoSign = viewModel::setAutoSign,
                                    onSign = { signActivity = it },
                                )
                                Destination.COURSES -> CoursesScreen(state, viewModel::saveCourseSelection)
                                Destination.RECORDS -> RecordsScreen(state) { signActivity = it }
                                Destination.SETTINGS -> SettingsScreen(
                                    state = state,
                                    config = signConfig,
                                    onMode = viewModel::setMode,
                                    onBootRestore = viewModel::setBootRestore,
                                    onInterval = viewModel::setIntervalSeconds,
                                    onSaveDefaultLocation = viewModel::saveDefaultLocation,
                                    onLogout = viewModel::logout,
                                )
                            }
                        } else {
                            LoginScreen(
                                busy = state.busy,
                                error = state.error,
                                onLogin = viewModel::login,
                            )
                        }
                    }
                }
            }
        }
    }
    if (debugMapOpen) {
        MapPickerDialog(
            initialLatitude = "",
            initialLongitude = "",
            onDismiss = { debugMapOpen = false },
            onUse = { _, _ -> debugMapOpen = false },
        )
    }
    signActivity?.let { activity ->
        key(activity.activeId) {
            SignActivityDialog(
                activity = activity,
                config = signConfig,
                previewOnly = debugSignPreviewKind != null,
                onDismiss = {
                    signActivity = null
                    viewModel.dismissSignNotice()
                },
                onUploadPhoto = { mimeType, data ->
                    if (debugSignPreviewKind != null) error("本地预览模式不会上传图片")
                    viewModel.uploadPhotoForActivity(activity, mimeType, data)
                },
                onSign = { input, onComplete ->
                    if (debugSignPreviewKind != null) {
                        onComplete(false, "本地预览模式，不会提交签到")
                    } else {
                        viewModel.signNow(activity, input, onComplete)
                    }
                },
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                text = message,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭提示")
            }
        }
    }
}

@Composable
private fun SignNoticeBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(message, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭成功提示")
            }
        }
    }
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    error: String,
    onLogin: (String, String) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("登录学习通", style = MaterialTheme.typography.headlineSmall)
            Text(
                "登录后选择需要巡检的课程。账号仅保存在本机加密区，退出时会清除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                label = { Text("手机号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                label = { Text("学习通密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { onLogin(phone.trim(), password); password = "" },
                enabled = !busy && phone.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("登录并获取课程")
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OverviewScreen(
    state: MonitorUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAutoSign: (Boolean) -> Unit,
    onSign: (ActivityCard) -> Unit,
) {
    val latest = state.activities.firstOrNull() ?: state.latestActivity
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("巡检概览", style = MaterialTheme.typography.headlineSmall)
            MonitorStatusPanel(state, onStart, onStop)
            LatestActivityPanel(latest, onSign)
            AutoSignRow(state.autoSign, onAutoSign)
            TodaySummary(state.scanCountToday, state.signCountToday)
        }
    }
}

@Composable
private fun MonitorStatusPanel(state: MonitorUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val running = state.monitorWanted
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(11.dp),
                    shape = CircleShape,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    content = {},
                )
                Column(Modifier.weight(1f)) {
                    Text(if (running) "巡检已开启" else "巡检已停止", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${if (state.mode == MonitorMode.BACKGROUND) "后台" else "前台"} · 每 ${state.intervalSeconds} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止") }
                } else {
                    Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) { Text("开始") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    if (state.lastScanMillis > 0L) "上次巡检 · ${formatTime(state.lastScanMillis)}" else "上次巡检 · 暂无记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LatestActivityPanel(activity: ActivityCard?, onSign: (ActivityCard) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("最新签到活动", style = MaterialTheme.typography.titleMedium)
        if (activity == null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("暂时没有新活动", style = MaterialTheme.typography.titleSmall)
                        Text("开始巡检后，新发现的签到会显示在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            val flow = activitySignFlow(activity.kind)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(activity.name.ifBlank { activity.kindName }, style = MaterialTheme.typography.titleMedium)
                            Text(activity.kindName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(activity.courseTitle.ifBlank { "课程信息暂缺" }, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text(formatStartedAt(activity.startedAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(activity.freshness(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { onSign(activity) },
                        enabled = !activity.signed && flow != ActivitySignFlow.UNSUPPORTED,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) {
                        Text(if (activity.signed) "已签到" else flow.actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoSignRow(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.clickable { onChanged(!enabled) }.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("自动签到", style = MaterialTheme.typography.titleSmall)
                Text("只自动处理普通、照片和已设默认位置的定位签到；二维码、手势和签到码需现场操作。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onChanged)
        }
    }
}

@Composable
private fun TodaySummary(scanCount: Int, signCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryItem("今日巡检", "$scanCount 次")
            VerticalDivider(
                modifier = Modifier.height(34.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SummaryItem("成功签到", "$signCount 次")
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CoursesScreen(state: MonitorUiState, onSelectionChanged: (List<CourseChoice>) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("巡检课程", style = MaterialTheme.typography.headlineSmall)
            Text("勾选需要检查的课程；至少保留一门。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.availableCourses.isEmpty()) {
                EmptyPanel("还没有课程", "登录后会从学习通读取课程列表。")
            } else {
                Text("已选 ${state.availableCourses.count { it.selected }} / ${state.availableCourses.size}", style = MaterialTheme.typography.labelLarge)
                state.availableCourses.forEach { course ->
                    CourseRow(course) { selected ->
                        val next = state.availableCourses.map { row ->
                            if (row.courseId == course.courseId && row.classId == course.classId) row.copy(selected = selected) else row
                        }
                        onSelectionChanged(next.filter { it.selected })
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseRow(course: CourseChoice, onSelected: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.clickable { onSelected(!course.selected) }.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(course.title.ifBlank { "未命名课程" }, style = MaterialTheme.typography.titleSmall)
                Text("课程 ${course.courseId} · 班级 ${course.classId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = course.selected, onCheckedChange = onSelected)
        }
    }
}

@Composable
private fun RecordsScreen(state: MonitorUiState, onSign: (ActivityCard) -> Unit) {
    val activities = state.activities.ifEmpty { listOfNotNull(state.latestActivity) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("最近活动", style = MaterialTheme.typography.headlineSmall)
            Text("活动列表随最近一次巡检更新。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (activities.isEmpty()) EmptyPanel("暂时没有活动", "巡检发现签到活动后，会显示在这里。")
            else activities.forEach { activity -> ActivityRow(activity, onSign) }
        }
    }
}

@Composable
private fun ActivityRow(activity: ActivityCard, onSign: (ActivityCard) -> Unit) {
    val flow = activitySignFlow(activity.kind)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(activity.name.ifBlank { activity.kindName }, style = MaterialTheme.typography.titleSmall)
                    Text(activity.courseTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(activity.kindName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatStartedAt(activity.startedAtMillis)} · ${activity.freshness()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { onSign(activity) },
                    enabled = !activity.signed && flow != ActivitySignFlow.UNSUPPORTED,
                ) {
                    Text(if (activity.signed) "已签到" else flow.actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MonitorUiState,
    config: com.x1rain0.chaoxingstudio.data.MonitorConfig,
    onMode: (MonitorMode) -> Unit,
    onBootRestore: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
    onSaveDefaultLocation: (String, String, String) -> Unit,
    onLogout: () -> Unit,
) {
    var latitude by rememberSaveable(config.latitude) { mutableStateOf(config.latitude) }
    var longitude by rememberSaveable(config.longitude) { mutableStateOf(config.longitude) }
    var address by rememberSaveable(config.address) { mutableStateOf(config.address) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Text("巡检方式", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = state.mode == MonitorMode.FOREGROUND,
                    onClick = { onMode(MonitorMode.FOREGROUND) },
                    label = { Text("前台") },
                )
                FilterChip(
                    selected = state.mode == MonitorMode.BACKGROUND,
                    onClick = { onMode(MonitorMode.BACKGROUND) },
                    label = { Text("后台") },
                )
            }
            Text(
                if (state.mode == MonitorMode.BACKGROUND) "后台运行会显示常驻通知；新发现签到活动时才发送提醒。" else "前台巡检仅在应用处于前台时运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("巡检间隔 · ${state.intervalSeconds} 秒", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = state.intervalSeconds.toFloat(),
                    onValueChange = { raw ->
                        val snapped = (((raw.roundToInt() - 30 + 15) / 30) * 30 + 30).coerceIn(30, 300)
                        onInterval(snapped)
                    },
                    valueRange = 30f..300f,
                    steps = 8,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("30 秒", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5 分钟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SettingSwitch(
                title = "开机后恢复巡检",
                description = "独立开关，默认关闭；仅后台模式且上次巡检仍开启时生效。",
                checked = state.bootRestore,
                onChanged = onBootRestore,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("默认签到位置", style = MaterialTheme.typography.titleMedium)
            Text(
                "这里保存常用位置。二维码、手势和签到码会在对应活动中现场处理，不会存到设置里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it; saved = false },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    label = { Text("纬度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it; saved = false },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    label = { Text("经度") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            OutlinedButton(
                onClick = { mapOpen = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("在地图上选点")
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                label = { Text("位置描述") },
                singleLine = true,
            )
            Button(
                onClick = {
                    onSaveDefaultLocation(latitude, longitude, address)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            ) { Text(if (saved) "默认位置已保存" else "保存默认位置") }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("账号", style = MaterialTheme.typography.titleMedium)
            Text("当前账号：${state.accountName.ifBlank { "已登录" }}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("退出并清除本机凭据") }
        }
    }
    if (mapOpen) {
        MapPickerDialog(
            initialLatitude = latitude,
            initialLongitude = longitude,
            onDismiss = { mapOpen = false },
            onUse = { lat, lng ->
                latitude = lat
                longitude = lng
                address = ""
                saved = false
                mapOpen = false
            },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.clickable { onChanged(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChanged)
        }
    }
}

@Composable
private fun EmptyPanel(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

private fun formatStartedAt(timestamp: Long): String =
    if (timestamp <= 0L) "开始时间未知"
    else "开始于 ${Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}"
