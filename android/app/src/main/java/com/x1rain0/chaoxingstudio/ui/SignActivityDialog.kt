package com.x1rain0.chaoxingstudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.ExperimentalGetImage
import androidx.annotation.OptIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.x1rain0.chaoxingstudio.data.ActivityCard
import com.x1rain0.chaoxingstudio.data.ActivitySignFlow
import com.x1rain0.chaoxingstudio.data.ManualSignInput
import com.x1rain0.chaoxingstudio.data.MonitorConfig
import com.x1rain0.chaoxingstudio.data.SelectedPhoto
import com.x1rain0.chaoxingstudio.data.isPhotoSelectionReady
import com.x1rain0.chaoxingstudio.data.readSelectedPhoto
import com.x1rain0.chaoxingstudio.data.activitySignFlow
import com.x1rain0.chaoxingstudio.data.appendGesturePoint
import com.x1rain0.chaoxingstudio.data.extractChaoxingEnc
import com.x1rain0.chaoxingstudio.data.gestureCode
import com.x1rain0.chaoxingstudio.data.gesturePathFromDigits
import com.x1rain0.chaoxingstudio.data.gesturePointAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun SignActivityDialog(
    activity: ActivityCard,
    config: MonitorConfig,
    previewOnly: Boolean = false,
    onDismiss: () -> Unit,
    onUploadPhoto: suspend (mimeType: String, data: ByteArray) -> String,
    onSign: (ManualSignInput, (Boolean, String) -> Unit) -> Unit,
) {
    val flow = activitySignFlow(activity.kind)
    var cameraOpen by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    var qrPasteExpanded by remember { mutableStateOf(false) }
    var qrText by remember { mutableStateOf("") }
    var enc by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var gesturePath by remember { mutableStateOf(emptyList<Int>()) }
    var gestureDigits by remember { mutableStateOf("") }
    var selectedPhoto by remember { mutableStateOf<SelectedPhoto?>(null) }
    var photoLoading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf("") }
    var uploadedPhotoObjectId by remember { mutableStateOf("") }
    var temporaryLatitude by remember { mutableStateOf("") }
    var temporaryLongitude by remember { mutableStateOf("") }
    var temporaryAddress by remember { mutableStateOf("") }
    var mapOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(activity.signed) }
    var resultMessage by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            photoLoading = true
            photoError = ""
            uploadedPhotoObjectId = ""
            resultMessage = ""
            coroutineScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { readSelectedPhoto(context, uri) } }
                result.onSuccess { selectedPhoto = it }
                    .onFailure {
                        selectedPhoto = null
                        photoError = it.message ?: "无法读取所选图片。"
                    }
                photoLoading = false
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraError = ""
            cameraOpen = true
        } else {
            cameraError = "没有相机权限；也可以粘贴学习通签到二维码内容。"
        }
    }

    val defaultLocationReady = hasValidLocation(config.latitude, config.longitude)
    val temporaryLocationReady = hasValidLocation(temporaryLatitude, temporaryLongitude)
    val signEnabled = when (flow) {
        ActivitySignFlow.GENERAL -> true
        ActivitySignFlow.PHOTO -> isPhotoSelectionReady(
            hasSelection = selectedPhoto != null,
            hasDecodedPreview = selectedPhoto?.preview != null,
            loadingSelection = photoLoading,
            uploading = submitting,
            hasError = photoError.isNotBlank(),
        )
        ActivitySignFlow.QR -> enc.isNotBlank()
        ActivitySignFlow.GESTURE -> gestureCode(gesturePath).length >= 4
        ActivitySignFlow.LOCATION -> temporaryLocationReady || defaultLocationReady
        ActivitySignFlow.CODE -> code.isNotBlank()
        ActivitySignFlow.UNSUPPORTED -> false
    } && !activity.signed && !completed && !submitting && !previewOnly

    Dialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !submitting,
            dismissOnClickOutside = !submitting,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(flow.actionLabel, style = MaterialTheme.typography.titleLarge)
                        Text(activity.kindName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDismiss, enabled = !submitting) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭签到处理")
                    }
                }
                Text(
                    "${activity.name.ifBlank { activity.kindName }} · ${activity.courseTitle.ifBlank { "课程信息暂缺" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (previewOnly) {
                    Text(
                        "本地预览模式：不会向学习通提交签到。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                when (flow) {
                    ActivitySignFlow.GENERAL -> Text("确认后会重新检查这条活动仍有效，再提交普通签到。", style = MaterialTheme.typography.bodyMedium)
                    ActivitySignFlow.PHOTO -> {
                        Text(
                            "从手机相册选择一张 JPG 或 PNG。图片先在本机预览；点击确认后才会上传到学习通，并用于当前这条活动。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = !photoLoading && !submitting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        ) {
                            Text(if (selectedPhoto == null) "从相册选择照片" else "重新选择照片")
                        }
                        if (photoLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("正在读取并生成本地预览…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selectedPhoto != null) {
                            val photo = selectedPhoto!!
                            Box(
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = photo.preview.asImageBitmap(),
                                    contentDescription = "所选照片的本地预览",
                                    modifier = Modifier.fillMaxWidth().height(220.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Text(
                                "${photo.mimeType.substringAfter('/').uppercase()} · ${(photo.bytes.size / 1024).coerceAtLeast(1)} KB · 仅在本次窗口暂存",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (uploadedPhotoObjectId.isNotBlank()) {
                                Text(
                                    "照片已上传。若签到未完成，再次确认会复用本次上传，不会重复传图。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else if (!photoLoading && photoError.isBlank()) {
                            Text("尚未选择照片。没有本地预览时不会上传，也不会提交签到。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (photoError.isNotBlank()) {
                            Text(photoError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            when {
                                previewOnly -> "本地预览模式：图片只在本机显示，不会上传或提交。"
                                uploadedPhotoObjectId.isBlank() -> "确认按钮会先上传所选图片，再提交本次签到；上传失败时不会提交。"
                                else -> "所选图片只用于当前活动；关闭窗口后不会把图片或图片ID保存为设置。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ActivitySignFlow.QR -> {
                        Text("扫描老师当前展示的学习通签到二维码。二维码内容只用于本次提交，不会保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cameraOpen) {
                            QrCameraScanner(
                                modifier = Modifier.fillMaxWidth().height(232.dp).clip(MaterialTheme.shapes.large),
                                onResult = { raw ->
                                    val parsed = extractChaoxingEnc(raw)
                                    if (parsed == null) {
                                        inputError = "二维码中没有有效的学习通签到参数，请重扫或粘贴二维码内容。"
                                    } else {
                                        enc = parsed
                                        qrText = ""
                                        qrPasteExpanded = false
                                        inputError = ""
                                        cameraOpen = false
                                    }
                                },
                                onCameraError = { cameraError = it },
                            )
                            OutlinedButton(onClick = { cameraOpen = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text("关闭相机")
                            }
                        } else {
                            Button(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                    if (granted) cameraOpen = true else cameraPermission.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (enc.isNotBlank()) "重新扫码" else "打开相机扫码")
                            }
                            TextButton(
                                onClick = { qrPasteExpanded = !qrPasteExpanded },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(if (qrPasteExpanded) "收起粘贴方式" else "相机不可用？粘贴二维码内容")
                            }
                            if (qrPasteExpanded) {
                                OutlinedTextField(
                                    value = qrText,
                                    onValueChange = { qrText = it.take(4_096); inputError = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("粘贴签到二维码内容") },
                                    supportingText = { Text(if (enc.isNotBlank()) "已识别本次二维码参数" else "只提取签到参数；临时令牌不会显示或保存") },
                                    singleLine = false,
                                    maxLines = 3,
                                )
                                OutlinedButton(
                                    onClick = {
                                        val parsed = extractChaoxingEnc(qrText)
                                        if (parsed == null) {
                                            inputError = "没有找到有效的学习通签到参数，请检查二维码内容。"
                                        } else {
                                            enc = parsed
                                            qrText = ""
                                            qrPasteExpanded = false
                                            inputError = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text("识别粘贴内容") }
                            }
                        }
                        if (cameraError.isNotBlank()) Text(cameraError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    ActivitySignFlow.GESTURE -> {
                        Text("按老师示范顺序连接至少 4 个点；手势只用于当前这次签到。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        GesturePad(
                            path = gesturePath,
                            onPathChanged = { path ->
                                gesturePath = path
                                gestureDigits = gestureCode(path)
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (gesturePath.isEmpty()) "在点阵内连续滑动绘制" else "已连接 ${gesturePath.size} 个点", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { gesturePath = emptyList(); gestureDigits = ""; inputError = "" }) { Text("清除") }
                        }
                        OutlinedTextField(
                            value = gestureDigits,
                            onValueChange = { raw ->
                                gestureDigits = raw.filter { it in '1'..'9' }.take(9)
                                gesturePath = gesturePathFromDigits(gestureDigits)
                                inputError = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("手势数字序列（1–9）") },
                            supportingText = { Text("也可用键盘或屏幕阅读器输入；跨过的中间点会自动补入") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    ActivitySignFlow.LOCATION -> {
                        Text("默认位置用于常规巡检；如果老师要求现场位置，可只为这次签到临时选点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (temporaryLocationReady) {
                            LocationSummary("本次临时位置", temporaryLatitude, temporaryLongitude, temporaryAddress.ifBlank { "地图选点" })
                        } else if (defaultLocationReady) {
                            LocationSummary("默认位置", config.latitude, config.longitude, config.address.ifBlank { "未填写位置描述" })
                        } else {
                            Text("尚未设置默认位置，请为本次签到选择一个位置。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        OutlinedButton(onClick = { mapOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (temporaryLocationReady) "重新选本次位置" else "为本次签到选点")
                        }
                        if (temporaryLocationReady) {
                            OutlinedTextField(
                                value = temporaryAddress,
                                onValueChange = { temporaryAddress = it.take(120) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("本次位置描述（可选）") },
                                singleLine = true,
                            )
                        }
                    }
                    ActivitySignFlow.CODE -> {
                        Text("输入老师当前提供的签到码。内容只在这次活动窗口内使用；开头的 0 会原样保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.take(32); inputError = "" },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("本次签到码") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        )
                    }
                    ActivitySignFlow.UNSUPPORTED -> Text("此版本暂不支持该签到类型。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }

                if (inputError.isNotBlank()) Text(inputError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (resultMessage.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = if (completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (completed) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(resultMessage, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (activity.signed && !completed) {
                    Text("这条活动已记录为签到完成。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (completed || activity.signed) "完成" else "取消") }
                    Button(
                        onClick = {
                            resultMessage = ""
                            inputError = ""
                            val complete: (Boolean, String) -> Unit = { succeeded, message ->
                                submitting = false
                                resultMessage = message.ifBlank { if (succeeded) "签到成功" else "签到未完成" }
                                completed = succeeded
                            }
                            if (flow == ActivitySignFlow.PHOTO) {
                                if (previewOnly) {
                                    inputError = "本地预览模式不会上传图片或提交签到。"
                                } else {
                                    val photo = selectedPhoto
                                    if (photo == null) {
                                        photoError = "请先选择并预览照片。"
                                    } else {
                                        submitting = true
                                        coroutineScope.launch {
                                            val objectId = uploadedPhotoObjectId.ifBlank {
                                                try {
                                                    onUploadPhoto(photo.mimeType, photo.bytes).also { uploadedPhotoObjectId = it }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    submitting = false
                                                    resultMessage = error.message?.let { "照片上传失败：$it" }
                                                        ?: "照片上传失败。请检查网络后重试。"
                                                    return@launch
                                                }
                                            }
                                            if (objectId.isBlank()) {
                                                submitting = false
                                                resultMessage = "照片上传未返回有效文件编号，请重试。"
                                            } else {
                                                onSign(ManualSignInput(photoObjectId = objectId), complete)
                                            }
                                        }
                                    }
                                }
                            } else {
                                submitting = true
                                val manualInput = when (flow) {
                                    ActivitySignFlow.QR -> ManualSignInput(enc = enc)
                                    ActivitySignFlow.GESTURE -> ManualSignInput(signCode = gestureCode(gesturePath))
                                    ActivitySignFlow.CODE -> ManualSignInput(signCode = code)
                                    ActivitySignFlow.LOCATION -> if (temporaryLocationReady) {
                                        ManualSignInput(
                                            latitude = temporaryLatitude,
                                            longitude = temporaryLongitude,
                                            address = temporaryAddress,
                                        )
                                    } else ManualSignInput()
                                    else -> ManualSignInput()
                                }
                                onSign(manualInput, complete)
                            }
                        },
                        enabled = signEnabled,
                        modifier = Modifier.heightIn(min = 50.dp),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                previewOnly -> "仅预览，不提交"
                                completed || activity.signed -> "已签到"
                                else -> flow.submitLabel
                            },
                        )
                    }
                }
            }
        }
    }

    if (mapOpen) {
        MapPickerDialog(
            initialLatitude = temporaryLatitude.ifBlank { config.latitude },
            initialLongitude = temporaryLongitude.ifBlank { config.longitude },
            onDismiss = { mapOpen = false },
            onUse = { lat, lng ->
                temporaryLatitude = lat
                temporaryLongitude = lng
                temporaryAddress = ""
                mapOpen = false
            },
        )
    }
}

@Composable
private fun LocationSummary(title: String, latitude: String, longitude: String, address: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("$latitude, $longitude", style = MaterialTheme.typography.bodyMedium)
            Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GesturePad(path: List<Int>, onPathChanged: (List<Int>) -> Unit) {
    val latestOnPathChanged by rememberUpdatedState(onPathChanged)
    val shape = RoundedCornerShape(24.dp)
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val selected = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier.fillMaxWidth().height(276.dp)
            .clip(shape)
            .background(surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(Unit) {
                var activePath = emptyList<Int>()
                detectDragGestures(
                    onDragStart = { offset ->
                        activePath = gesturePointAt(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                            ?.let { appendGesturePoint(emptyList(), it) }
                            ?: emptyList()
                        latestOnPathChanged(activePath)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        gesturePointAt(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())?.let { point ->
                            val updated = appendGesturePoint(activePath, point)
                            if (updated != activePath) {
                                activePath = updated
                                latestOnPathChanged(activePath)
                            }
                        }
                    },
                    onDragEnd = {},
                )
            },
    ) {
        val insetX = size.width / 6f
        val insetY = size.height / 6f
        val stepX = size.width / 3f
        val stepY = size.height / 3f
        val centers = (1..9).map { point ->
            val row = (point - 1) / 3
            val column = (point - 1) % 3
            Offset(insetX + column * stepX, insetY + row * stepY)
        }
        path.zipWithNext().forEach { (from, to) ->
            drawLine(line, centers[from - 1], centers[to - 1], strokeWidth = 5.dp.toPx())
        }
        centers.forEachIndexed { index, center ->
            val active = index + 1 in path
            drawCircle(
                color = if (active) selected else dot,
                radius = if (active) 15.dp.toPx() else 10.dp.toPx(),
                center = center,
            )
            if (active) drawCircle(color = surface, radius = 4.dp.toPx(), center = center)
            else drawCircle(color = surface, radius = 4.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

private fun hasValidLocation(latitude: String, longitude: String): Boolean {
    val lat = latitude.trim().toDoubleOrNull() ?: return false
    val lng = longitude.trim().toDoubleOrNull() ?: return false
    return lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}
