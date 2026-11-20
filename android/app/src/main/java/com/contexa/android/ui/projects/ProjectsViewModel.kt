package com.contexa.android.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.CreateProjectRequest
import com.contexa.android.api.ProjectSummary
import com.contexa.android.api.Role
import com.contexa.android.api.userMessage
import com.contexa.android.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun roleLabel(role: Role): String = when (role) {
    Role.OWNER -> "소유자"
    Role.REVIEWER -> "검토자"
    Role.MEMBER -> "구성원"
}

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val loadError: String? = null,
    val creating: Boolean = false,
    val createError: String? = null,
)

/** 로그인 직후 화면: 내 프로젝트를 고르거나 새로 만든다. */
class ProjectsViewModel(private val api: AuthApi, private val session: SessionStore) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val projects = api.listProjects()
                _state.update { it.copy(loading = false, projects = projects) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = userMessage(e)) }
            }
        }
    }

    /** @param onCreated 새 프로젝트의 id와 이름을 받는다. */
    fun create(title: String, onCreated: (ProjectSummary) -> Unit) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || _state.value.creating) return
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val id = api.createProject(CreateProjectRequest(trimmed)).id
                val created = ProjectSummary(id = id, title = trimmed, role = Role.OWNER)
                _state.update { it.copy(creating = false, projects = it.projects + created) }
                onCreated(created)
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, createError = userMessage(e)) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }
}
