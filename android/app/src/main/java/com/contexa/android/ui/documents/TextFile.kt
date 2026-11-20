package com.contexa.android.ui.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 기기에서 읽어 들일 수 있는 가장 큰 텍스트 파일(웹과 같다). */
const val MAX_TEXT_FILE_BYTES = 5 * 1024 * 1024

const val NOT_TEXT_MESSAGE = "텍스트(.txt) 파일만 불러올 수 있습니다. PDF·Word는 아직 지원하지 않습니다."
const val TOO_LARGE_MESSAGE = "5MB보다 큰 파일은 불러올 수 없습니다."
const val UNREADABLE_MESSAGE = "파일을 읽지 못했습니다."

sealed interface TextFileResult {
    data class Loaded(val text: String, val fileName: String) : TextFileResult
    data class Rejected(val message: String) : TextFileResult
}

fun isTextFile(mimeType: String?, fileName: String): Boolean =
    mimeType == "text/plain" || fileName.endsWith(".txt", ignoreCase = true)

/** UTF-8로 푼다. 맨 앞의 BOM은 뗀다. */
fun decodeTextFile(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).removePrefix("﻿")

/** 고른 파일을 읽는다. server로 파일을 보내지 않고 본문 칸만 채운다. */
suspend fun readTextFile(context: Context, uri: Uri): TextFileResult = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    try {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        if (!isTextFile(resolver.getType(uri), name)) return@withContext TextFileResult.Rejected(NOT_TEXT_MESSAGE)

        // 크기를 알려 주지 않는 제공자도 있으므로, 상한보다 1바이트 더 읽어 보고 판단한다.
        val bytes = resolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_TEXT_FILE_BYTES + 1) }
            ?: return@withContext TextFileResult.Rejected(UNREADABLE_MESSAGE)
        if (bytes.size > MAX_TEXT_FILE_BYTES) return@withContext TextFileResult.Rejected(TOO_LARGE_MESSAGE)
        TextFileResult.Loaded(decodeTextFile(bytes), name)
    } catch (_: IOException) {
        TextFileResult.Rejected(UNREADABLE_MESSAGE)
    } catch (_: SecurityException) {
        TextFileResult.Rejected(UNREADABLE_MESSAGE)
    }
}

/** InputStream.readNBytes는 API 33부터라 직접 읽는다. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** 시스템 파일 선택기로 .txt를 골라 읽는다. */
@Composable
fun TextFileButton(onLoad: (text: String, fileName: String) -> Unit, onReject: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = readTextFile(context, uri)) {
                    is TextFileResult.Loaded -> onLoad(result.text, result.fileName)
                    is TextFileResult.Rejected -> onReject(result.message)
                }
            }
        }
    }
    TextButton(onClick = { picker.launch(arrayOf("text/plain")) }) { Text("텍스트 파일 불러오기") }
}
