package com.wordbattle.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

data class OcrWord(val word: String, val meaning: String, val rawLine: String)

@Composable
fun OcrTestScreen(
    activity: android.app.Activity,
    onBack: () -> Unit
) {
    var ocrText by remember { mutableStateOf("") }
    var parsedWords by remember { mutableStateOf<List<OcrWord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val stream = activity.contentResolver.openInputStream(it)
                bitmap = BitmapFactory.decodeStream(stream)
                stream?.close()
                loading = true
                val image = InputImage.fromBitmap(bitmap!!, 0)
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        ocrText = visionText.text
                        val lines = visionText.textBlocks.flatMap { block ->
                            block.lines
                        }.map { it.text }
                        parsedWords = lines.mapNotNull { line ->
                            parseWordLine(line)
                        }.distinctBy { it.word.lowercase() }
                        errorMsg = null
                        loading = false
                    }
                    .addOnFailureListener { e ->
                        errorMsg = "OCR 失败: ${e.message}"
                        loading = false
                    }
            } catch (e: Exception) {
                errorMsg = "加载图片失败: ${e.message}"
                loading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("OCR 词库识别测试", fontSize = 20.sp,
            style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { galleryLauncher.launch("image/*") },
                modifier = Modifier.weight(1f)
            ) {
                Text("选图")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("返回")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.height(8.dp))
            Text("识别中...", modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        bitmap?.let { bmp ->
            Spacer(modifier = Modifier.height(8.dp))
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Selected image",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        errorMsg?.let { msg ->
            Text("错误: $msg", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (ocrText.isNotEmpty()) {
            Text("OCR 原始文本:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(ocrText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (parsedWords.isNotEmpty()) {
            Text("解析结果 (${parsedWords.size} 个词):", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(parsedWords) { entry ->
                    Row(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${entry.word}  ", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(entry.meaning, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    Divider(thickness = 0.5.dp)
                }
            }
        }

        if (ocrText.isEmpty() && !loading && bitmap == null) {
            Spacer(modifier = Modifier.weight(1f))
            Text("点击上方\"选图\"选择课本图片进行识别",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

fun parseWordLine(line: String): OcrWord? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val parts = trimmed.split(Regex("\\s+"))
    if (parts.size < 2) {
        if (parts[0].matches(Regex("^[a-zA-Z]+$"))) {
            return OcrWord(parts[0], "", line)
        }
        return null
    }

    var wordEnd = 0
    for (i in parts.indices) {
        if (parts[i].matches(Regex("^[a-zA-Z]+$"))) {
            wordEnd = i + 1
            break
        } else {
            return null
        }
    }

    if (wordEnd == 0) return null

    val word = parts[0]
    val meaning = parts.subList(wordEnd, parts.size).joinToString(" ")
    return OcrWord(word, meaning, line)
}