package com.contexa.android.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.contexa.android.ui.components.PrimaryButton

@Composable
fun SignInScreen(viewModel: AuthViewModel, expired: Boolean, onGoSignUp: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(SignInErrors()) }

    AuthLayout(title = "로그인", subtitle = "이메일과 비밀번호로 로그인하세요.") {
        if (expired && state.serverError == null) {
            Text(
                "로그인이 만료됐습니다. 다시 로그인하세요.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        EmailField(email, { email = it }, errors.email)
        PasswordField("비밀번호", password, { password = it }, errors.password, ImeAction.Done)
        ServerError(state.serverError)
        SubmitButton("로그인", state.loading) {
            errors = validateSignIn(email, password)
            if (errors.ok) viewModel.signIn(email, password)
        }
        TextButton(onClick = onGoSignUp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("계정이 없나요? 가입하기")
        }
    }
}

@Composable
fun SignUpScreen(viewModel: AuthViewModel, onGoSignIn: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(SignUpErrors()) }

    AuthLayout(title = "가입", subtitle = "가입하면 바로 프로젝트를 만들 수 있습니다.") {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("이름") },
            singleLine = true,
            isError = errors.displayName != null,
            supportingText = errors.displayName?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        EmailField(email, { email = it }, errors.email)
        PasswordField("비밀번호", password, { password = it }, errors.password, ImeAction.Next)
        PasswordField("비밀번호 확인", confirm, { confirm = it }, errors.confirmPassword, ImeAction.Done)
        ServerError(state.serverError)
        SubmitButton("가입하기", state.loading) {
            errors = validateSignUp(displayName, email, password, confirm)
            if (errors.ok) viewModel.signUp(displayName, email, password)
        }
        TextButton(onClick = onGoSignIn, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("이미 계정이 있나요? 로그인")
        }
    }
}

@Composable
private fun AuthLayout(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Contexa", style = MaterialTheme.typography.headlineMedium)
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                content()
            }
        }
    }
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text("이메일") },
        placeholder = { Text("name@example.com") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    imeAction: ImeAction,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) { Text(if (visible) "숨기기" else "보기") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerError(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubmitButton(label: String, loading: Boolean, onClick: () -> Unit) {
    PrimaryButton(label, onClick = onClick, loading = loading, modifier = Modifier.fillMaxWidth())
}
