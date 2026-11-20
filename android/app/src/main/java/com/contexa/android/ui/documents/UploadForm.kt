package com.contexa.android.ui.documents

import com.contexa.android.api.DocumentInput
import com.contexa.android.api.DocumentKind
import com.contexa.android.api.Visibility

/** 문서 올리기 폼의 값과 검사. server가 받는 DocumentInput으로 바꾸는 규칙도 여기 둔다. */
data class UploadFormValues(
    val title: String = "",
    val kind: DocumentKind = DocumentKind.TRANSCRIPT,
    /** 언어 코드, 또는 MIXED_LANGUAGE */
    val language: String = "ko",
    val visibility: Visibility = Visibility.PROJECT,
    val externalOk: Boolean = false,
    val content: String = "",
)

data class UploadFormErrors(val title: String? = null, val content: String? = null) {
    val ok: Boolean get() = title == null && content == null
}

const val EMPTY_CONTENT_MESSAGE = "본문을 붙여 넣거나 파일을 불러오세요."

fun validateUpload(values: UploadFormValues): UploadFormErrors = UploadFormErrors(
    title = if (values.title.isBlank()) "제목을 입력하세요." else null,
    content = if (values.content.isBlank()) EMPTY_CONTENT_MESSAGE else null,
)

/** 폼 값 → server가 받는 문서. 섞인 언어는 null, 개인 메모는 외부 공유를 끈다. */
fun toDocumentInput(values: UploadFormValues): DocumentInput = DocumentInput(
    title = values.title.trim(),
    kind = values.kind,
    language = if (values.language == MIXED_LANGUAGE) null else values.language,
    visibility = values.visibility,
    externalOk = values.visibility == Visibility.PROJECT && values.externalOk,
    content = values.content,
)

/** 불러온 파일 이름으로 빈 제목을 채울 때 쓴다. */
fun titleFromFileName(fileName: String): String = fileName.replace(Regex("\\.txt$", RegexOption.IGNORE_CASE), "")
