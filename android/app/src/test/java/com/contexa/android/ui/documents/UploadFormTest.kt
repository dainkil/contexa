package com.contexa.android.ui.documents

import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFormTest {

    @Test
    fun `제목은 앞뒤 공백을 떼고 본문은 쓴 그대로 보낸다`() {
        val body = "  민지: 예산 승인 전까지 인쇄는 보류입니다.\n"
        assertEquals(
            DocumentInput(
                title = "3차 회의",
                kind = DocumentKind.TRANSCRIPT,
                language = "ko",
                visibility = Visibility.PROJECT,
                externalOk = false,
                content = body,
            ),
            toDocumentInput(UploadFormValues(title = "  3차 회의  ", content = body)),
        )
    }

    @Test
    fun `여러 언어가 섞인 문서는 언어를 null로 보낸다`() {
        assertNull(toDocumentInput(UploadFormValues(title = "t", content = "c", language = MIXED_LANGUAGE)).language)
    }

    @Test
    fun `개인 메모는 외부 공유를 켜지 않는다`() {
        assertFalse(
            toDocumentInput(
                UploadFormValues(title = "t", content = "c", visibility = Visibility.PRIVATE, externalOk = true),
            ).externalOk,
        )
        assertTrue(toDocumentInput(UploadFormValues(title = "t", content = "c", externalOk = true)).externalOk)
    }

    @Test
    fun `제목과 본문이 비면 막는다`() {
        val errors = validateUpload(UploadFormValues(title = "  ", content = " \n"))
        assertEquals("제목을 입력하세요.", errors.title)
        assertEquals(EMPTY_CONTENT_MESSAGE, errors.content)
        assertFalse(errors.ok)
        assertTrue(validateUpload(UploadFormValues(title = "t", content = "c")).ok)
    }

    @Test
    fun `텍스트 파일 - 이름에서 제목을 만들고, 종류와 BOM을 가린다`() {
        assertEquals("3차 회의", titleFromFileName("3차 회의.TXT"))
        assertEquals("notes.md", titleFromFileName("notes.md"))
        assertTrue(isTextFile("text/plain", "무제"))
        assertTrue(isTextFile("application/octet-stream", "a.txt"))
        assertFalse(isTextFile("application/pdf", "a.pdf"))
        assertEquals("안녕", decodeTextFile(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "안녕".toByteArray()))
    }
}
