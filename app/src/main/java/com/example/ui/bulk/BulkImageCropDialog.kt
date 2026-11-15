package com.example.ui.bulk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

private enum class CropDragHandle {
    NONE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE,
    MOVE
}

@Composable
fun BulkImageCropDialog(
    item: BulkImageItem,
    onApplyCrop: (NormalizedCropRect) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var loadedImage by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(item.id) { mutableStateOf(false) }

    val initialRect = item.cropRect ?: NormalizedCropRect()
    var cropLeft by rememberSaveable(item.id) { mutableFloatStateOf(initialRect.left) }
    var cropTop by rememberSaveable(item.id) { mutableFloatStateOf(initialRect.top) }
    var cropRight by rememberSaveable(item.id) { mutableFloatStateOf(initialRect.right) }
    var cropBottom by rememberSaveable(item.id) { mutableFloatStateOf(initialRect.bottom) }

    LaunchedEffect(item.uri) {
        loadFailed = false
        val bitmap = withContext(Dispatchers.IO) {
            loadOrientedBitmap(context = context, uri = item.uri, maxDimension = 1600)
        }
        if (bitmap != null) {
            loadedImage = bitmap.asImageBitmap()
        } else {
            loadFailed = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0E))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("bulk_crop_dialog")
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cancel_crop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close crop",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Crop & Re-scan",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = {
                        cropLeft = 0f
                        cropTop = 0f
                        cropRight = 1f
                        cropBottom = 1f
                    },
                    modifier = Modifier.testTag("reset_crop_button")
                ) {
                    Text(
                        text = "Reset",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive image & crop frame area
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF141418))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val imageBitmap = loadedImage
                when {
                    loadFailed -> {
                        Text(
                            text = "Unable to load image for cropping.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }

                    imageBitmap == null -> {
                        CircularProgressIndicator(color = Color(0xFF00E676))
                    }

                    else -> {
                        val density = LocalDensity.current
                        val containerW = constraints.maxWidth.toFloat()
                        val containerH = constraints.maxHeight.toFloat()
                        val imgW = imageBitmap.width.toFloat().coerceAtLeast(1f)
                        val imgH = imageBitmap.height.toFloat().coerceAtLeast(1f)

                        val scale = minOf(containerW / imgW, containerH / imgH)
                        val drawnW = imgW * scale
                        val drawnH = imgH * scale
                        val offsetX = (containerW - drawnW) / 2f
                        val offsetY = (containerH - drawnH) / 2f
                        val imageRect = Rect(
                            offset = Offset(offsetX, offsetY),
                            size = Size(drawnW, drawnH)
                        )
                        val touchSlopPx = with(density) { 28.dp.toPx() }
                        val minSpan = 0.08f

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(imageRect) {
                                    var activeHandle = CropDragHandle.NONE
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            val selLeft = imageRect.left + cropLeft * imageRect.width
                                            val selTop = imageRect.top + cropTop * imageRect.height
                                            val selRight = imageRect.left + cropRight * imageRect.width
                                            val selBottom = imageRect.top + cropBottom * imageRect.height

                                            activeHandle = detectCropHandle(
                                                point = start,
                                                left = selLeft,
                                                top = selTop,
                                                right = selRight,
                                                bottom = selBottom,
                                                tolerancePx = touchSlopPx
                                            )
                                        },
                                        onDragEnd = { activeHandle = CropDragHandle.NONE },
                                        onDragCancel = { activeHandle = CropDragHandle.NONE },
                                        onDrag = { change, dragAmount ->
                                            if (activeHandle == CropDragHandle.NONE || imageRect.width <= 0f || imageRect.height <= 0f) {
                                                return@detectDragGestures
                                            }
                                            change.consume()
                                            val dx = dragAmount.x / imageRect.width
                                            val dy = dragAmount.y / imageRect.height

                                            when (activeHandle) {
                                                CropDragHandle.TOP_LEFT -> {
                                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSpan)
                                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSpan)
                                                }
                                                CropDragHandle.TOP_RIGHT -> {
                                                    cropRight = (cropRight + dx).coerceIn(cropLeft + minSpan, 1f)
                                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSpan)
                                                }
                                                CropDragHandle.BOTTOM_LEFT -> {
                                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSpan)
                                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + minSpan, 1f)
                                                }
                                                CropDragHandle.BOTTOM_RIGHT -> {
                                                    cropRight = (cropRight + dx).coerceIn(cropLeft + minSpan, 1f)
                                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + minSpan, 1f)
                                                }
                                                CropDragHandle.LEFT_EDGE -> {
                                                    cropLeft = (cropLeft + dx).coerceIn(0f, cropRight - minSpan)
                                                }
                                                CropDragHandle.RIGHT_EDGE -> {
                                                    cropRight = (cropRight + dx).coerceIn(cropLeft + minSpan, 1f)
                                                }
                                                CropDragHandle.TOP_EDGE -> {
                                                    cropTop = (cropTop + dy).coerceIn(0f, cropBottom - minSpan)
                                                }
                                                CropDragHandle.BOTTOM_EDGE -> {
                                                    cropBottom = (cropBottom + dy).coerceIn(cropTop + minSpan, 1f)
                                                }
                                                CropDragHandle.MOVE -> {
                                                    val w = cropRight - cropLeft
                                                    val h = cropBottom - cropTop
                                                    val newLeft = (cropLeft + dx).coerceIn(0f, 1f - w)
                                                    val newTop = (cropTop + dy).coerceIn(0f, 1f - h)
                                                    cropLeft = newLeft
                                                    cropRight = newLeft + w
                                                    cropTop = newTop
                                                    cropBottom = newTop + h
                                                }
                                                CropDragHandle.NONE -> Unit
                                            }
                                        }
                                    )
                                }
                        ) {
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Crop preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val selLeft = imageRect.left + cropLeft * imageRect.width
                                val selTop = imageRect.top + cropTop * imageRect.height
                                val selRight = imageRect.left + cropRight * imageRect.width
                                val selBottom = imageRect.top + cropBottom * imageRect.height
                                val selWidth = (selRight - selLeft).coerceAtLeast(1f)
                                val selHeight = (selBottom - selTop).coerceAtLeast(1f)

                                // Dim outer area outside the crop selection
                                val overlayPath = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(imageRect)
                                    addRect(Rect(selLeft, selTop, selRight, selBottom))
                                }
                                drawPath(
                                    path = overlayPath,
                                    color = Color.Black.copy(alpha = 0.62f)
                                )

                                // Rule-of-thirds guides
                                val thirdW = selWidth / 3f
                                val thirdH = selHeight / 3f
                                for (i in 1..2) {
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.28f),
                                        start = Offset(selLeft + thirdW * i, selTop),
                                        end = Offset(selLeft + thirdW * i, selBottom),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.28f),
                                        start = Offset(selLeft, selTop + thirdH * i),
                                        end = Offset(selRight, selTop + thirdH * i),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }

                                // Selection border
                                drawRect(
                                    color = Color(0xFF00E676),
                                    topLeft = Offset(selLeft, selTop),
                                    size = Size(selWidth, selHeight),
                                    style = Stroke(width = 2.dp.toPx())
                                )

                                // Corner L-brackets
                                val cornerLen = minOf(22.dp.toPx(), selWidth * 0.35f, selHeight * 0.35f)
                                val cornerStroke = 4.dp.toPx()
                                val cornerColor = Color(0xFF00E676)

                                // Top-left
                                drawLine(cornerColor, Offset(selLeft, selTop), Offset(selLeft + cornerLen, selTop), cornerStroke)
                                drawLine(cornerColor, Offset(selLeft, selTop), Offset(selLeft, selTop + cornerLen), cornerStroke)
                                // Top-right
                                drawLine(cornerColor, Offset(selRight - cornerLen, selTop), Offset(selRight, selTop), cornerStroke)
                                drawLine(cornerColor, Offset(selRight, selTop), Offset(selRight, selTop + cornerLen), cornerStroke)
                                // Bottom-left
                                drawLine(cornerColor, Offset(selLeft, selBottom), Offset(selLeft + cornerLen, selBottom), cornerStroke)
                                drawLine(cornerColor, Offset(selLeft, selBottom - cornerLen), Offset(selLeft, selBottom), cornerStroke)
                                // Bottom-right
                                drawLine(cornerColor, Offset(selRight - cornerLen, selBottom), Offset(selRight, selBottom), cornerStroke)
                                drawLine(cornerColor, Offset(selRight, selBottom - cornerLen), Offset(selRight, selBottom), cornerStroke)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom hint & Apply button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Drag corners or box to select text area",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )

                Button(
                    onClick = {
                        onApplyCrop(
                            NormalizedCropRect(
                                left = cropLeft,
                                top = cropTop,
                                right = cropRight,
                                bottom = cropBottom
                            )
                        )
                    },
                    enabled = loadedImage != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("apply_crop_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Crop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Crop & Extract",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun detectCropHandle(
    point: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    tolerancePx: Float
): CropDragHandle {
    val corners = listOf(
        CropDragHandle.TOP_LEFT to Offset(left, top),
        CropDragHandle.TOP_RIGHT to Offset(right, top),
        CropDragHandle.BOTTOM_LEFT to Offset(left, bottom),
        CropDragHandle.BOTTOM_RIGHT to Offset(right, bottom)
    )
    val closestCorner = corners
        .map { (handle, pos) -> handle to hypot(point.x - pos.x, point.y - pos.y) }
        .filter { it.second <= tolerancePx }
        .minByOrNull { it.second }
    if (closestCorner != null) return closestCorner.first

    if (point.y in (top - tolerancePx)..(bottom + tolerancePx)) {
        if (abs(point.x - left) <= tolerancePx) return CropDragHandle.LEFT_EDGE
        if (abs(point.x - right) <= tolerancePx) return CropDragHandle.RIGHT_EDGE
    }
    if (point.x in (left - tolerancePx)..(right + tolerancePx)) {
        if (abs(point.y - top) <= tolerancePx) return CropDragHandle.TOP_EDGE
        if (abs(point.y - bottom) <= tolerancePx) return CropDragHandle.BOTTOM_EDGE
    }
    if (point.x in left..right && point.y in top..bottom) {
        return CropDragHandle.MOVE
    }
    return CropDragHandle.NONE
}

fun loadOrientedBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    val resolver = context.contentResolver

    val orientation = try {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, boundsOptions)
    } ?: return null

    val rawW = boundsOptions.outWidth
    val rawH = boundsOptions.outHeight
    if (rawW <= 0 || rawH <= 0) return null

    var sampleSize = 1
    val largestSide = max(rawW, rawH)
    while (largestSide / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = resolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, decodeOptions)
    } ?: return null

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return decoded
    }

    val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (transformed !== decoded) {
        decoded.recycle()
    }
    return transformed
}
