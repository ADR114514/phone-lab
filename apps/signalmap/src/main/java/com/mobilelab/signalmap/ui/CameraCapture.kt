package com.mobilelab.signalmap.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilelab.core.ui.LinkButton
import com.mobilelab.core.ui.PrimaryButton
import com.mobilelab.core.ui.SecondaryButton
import com.mobilelab.core.ui.SectionCard
import com.mobilelab.core.ui.theme.LabColors
import com.mobilelab.signalmap.data.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX preview + capture card. The photo is stored as
 * photos/<pointId>_<stamp>.jpg inside the active session directory, exactly as
 * required by DATA_DICTIONARY 2.4. Photos are optional: skipping keeps
 * photo_file empty and the pipeline records "未拍照" in the note column.
 */
@Composable
fun CameraCaptureCard(
    pointId: String,
    photoRelativePath: String,
    photoFile: File?,
    cameraGranted: Boolean,
    cameraFeatureAvailable: Boolean,
    /** False while a measurement repeat is running: photo changes are locked. */
    interactionEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onNewTarget: () -> SurveyRepository.PhotoTarget?,
    onCaptured: (SurveyRepository.PhotoTarget) -> Unit,
    onClearPhoto: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    SectionCard(title = "点位照片") {
        if (!cameraFeatureAvailable) {
            Text(
                "此设备未提供相机，测点照片将跳过（photo_file 留空）。",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            return@SectionCard
        }
        if (!cameraGranted) {
            Text(
                "未授予相机权限。可授权后拍照，也可跳过——跳过时 CSV 的 photo_file 留空并在 note 中记录。",
                style = MaterialTheme.typography.bodySmall,
                color = LabColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "授予相机权限",
                    onClick = onRequestPermission,
                    enabled = interactionEnabled,
                )
                SecondaryButton("跳过拍照", onClick = onClearPhoto, enabled = interactionEnabled)
            }
            return@SectionCard
        }

        val previewUseCase = remember { Preview.Builder().build() }
        val imageCapture = remember {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        }
        val previewView = remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
        var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
        var cameraError by remember { mutableStateOf<String?>(null) }
        var capturing by remember { mutableStateOf(false) }

        LaunchedEffect(cameraGranted) {
            val view = previewView
            val provider = try {
                awaitCameraProvider(context)
            } catch (error: Exception) {
                cameraError = "相机初始化失败：${error.message}"
                return@LaunchedEffect
            }
            cameraProvider = provider
            runCatching {
                previewUseCase.surfaceProvider = view.surfaceProvider
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    imageCapture,
                )
                cameraError = null
            }.onFailure { error ->
                cameraError = "相机无法启动：${error.message}"
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                ContextCompat.getMainExecutor(context).execute {
                    runCatching { cameraProvider?.unbindAll() }
                }
            }
        }

        if (photoRelativePath.isNotEmpty() && photoFile != null) {
            PhotoThumb(file = photoFile)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "当前照片：$photoRelativePath",
                style = MaterialTheme.typography.labelSmall,
                color = LabColors.Success,
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LabColors.SurfaceHigh),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            val error = cameraError
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabColors.Error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = when {
                    capturing -> "保存中…"
                    photoRelativePath.isNotEmpty() -> "重拍"
                    else -> "拍照"
                },
                enabled = !capturing && interactionEnabled,
                loading = capturing,
                onClick = {
                    val target = onNewTarget()
                    if (target != null) {
                        capturing = true
                        val options = ImageCapture.OutputFileOptions.Builder(target.file).build()
                        imageCapture.targetRotation =
                            previewView.display?.rotation ?: Surface.ROTATION_0
                        imageCapture.takePicture(
                            options,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    onCaptured(target)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    runCatching { target.file.delete() }
                                    onError("拍照失败：${exception.message}")
                                }
                            },
                        )
                    }
                },
            )
            if (photoRelativePath.isNotEmpty()) {
                SecondaryButton(
                    text = "跳过照片",
                    onClick = onClearPhoto,
                    enabled = interactionEnabled,
                )
            }
        }
        HintTextLine(
            "照片保存为 photos/${pointId}_<时间戳>.jpg，路径写入 survey CSV 的 photo_file 列。",
        )
        if (!interactionEnabled) {
            HintTextLine("测量进行中，照片已锁定；本轮结束后可重拍或跳过。")
        }
    }
}

@Composable
private fun HintTextLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LabColors.TextSecondary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PhotoThumb(file: File) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file.absolutePath, key2 = file.lastModified()) {
        value = withContext(Dispatchers.IO) { loadSampledBitmap(file, 640) }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "点位照片预览",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

private fun loadSampledBitmap(file: File, maxSize: Int): Bitmap? = runCatching {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
        sample *= 2
    }
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation {
            runCatching { future.cancel(true) }
        }
    }