package com.contexa.android.ui.auth

/** server(IdentityAccess.MIN_PASSWORD_LENGTH)와 같은 값. */
const val MIN_PASSWORD_LENGTH = 8

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/** 칸마다의 오류 문장. 문제가 없는 칸은 null. */
data class SignInErrors(val email: String? = null, val password: String? = null) {
    val ok: Boolean get() = email == null && password == null
}

data class SignUpErrors(
    val displayName: String? = null,
    val email: String? = null,
    val password: String? = null,
    val confirmPassword: String? = null,
) {
    val ok: Boolean get() = displayName == null && email == null && password == null && confirmPassword == null
}

private fun emailError(email: String): String? = when {
    email.isEmpty() -> "이메일을 입력하세요."
    !EMAIL.matches(email) -> "이메일 형식이 아닙니다."
    else -> null
}

fun validateSignIn(email: String, password: String) = SignInErrors(
    email = emailError(email),
    password = if (password.isEmpty()) "비밀번호를 입력하세요." else null,
)

fun validateSignUp(displayName: String, email: String, password: String, confirmPassword: String) = SignUpErrors(
    displayName = if (displayName.isBlank()) "이름을 입력하세요." else null,
    email = emailError(email),
    password = when {
        password.isEmpty() -> "비밀번호를 입력하세요."
        password.length < MIN_PASSWORD_LENGTH -> "비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다."
        else -> null
    },
    confirmPassword = when {
        confirmPassword.isEmpty() -> "비밀번호를 한 번 더 입력하세요."
        confirmPassword != password -> "비밀번호가 서로 다릅니다."
        else -> null
    },
)
