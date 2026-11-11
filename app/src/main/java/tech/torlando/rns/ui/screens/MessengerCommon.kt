package tech.torlando.rns.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val bitmap = remember(content) { encodeQr(content) }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "QR code",
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun encodeQr(content: String): ImageBitmap? {
    if (content.isEmpty()) return null
    return try {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0),
        )
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun PeerAvatar(name: String, hash: String, size: Dp = 40.dp) {
    val hue = (hash.take(6).toLongOrNull(16) ?: 0L) % 360
    val color = Color.hsv(hue.toFloat(), 0.45f, 0.75f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** Human-readable age of an epoch-seconds timestamp. */
fun formatAgo(epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return "never"
    val delta = (System.currentTimeMillis() / 1000.0 - epochSeconds).toLong()
    return when {
        delta < 60 -> "just now"
        delta < 3600 -> "${delta / 60} min ago"
        delta < 86400 -> "${delta / 3600} h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))
    }
}

fun formatMessageTime(epochSeconds: Double): String {
    val date = Date((epochSeconds * 1000).toLong())
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).let { it.format(date) == it.format(Date()) }
    val pattern = if (sameDay) "HH:mm" else "MMM d, HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

fun shortHash(hash: String): String = if (hash.length > 16) "${hash.take(8)}…${hash.takeLast(8)}" else hash
