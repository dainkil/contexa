package com.contexa.android.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contexa.android.api.AuthApi
import com.contexa.android.api.CreateProjectRequest
import com.contexa.android.api.ProjectSummary
import com.contexa.android.api.Role
import com.contexa.android.api.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/**
 * 불러온 목록에 방금 만든 프로젝트가 아직 없으면 뒤에 붙인다.
 * 만들기 전에 시작한 불러오기가 늦게 끝나면 새 프로젝트가 빠진 목록이 오기 때문이다.
 */
fun mergeProjects(loaded: List<ProjectSummary>, created: List<ProjectSummary>): List<ProjectSummary> {
    val ids = loaded.map { it.id }.toSet()
    return loaded + created.filter { it.id !in ids }
}

/** 로그인 직후 화면: 내 프로젝트를 고르거나 새로 만든다. */
class ProjectsViewModel(private val api: AuthApi) : ViewModel() {

    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state

    private var loadJob: Job? = null

    /** 이 화면에서 만들었지만 아직 불러온 목록에 없는 프로젝트. */
    private var created: List<ProjectSummary> = emptyList()

    /**
     * 목록을 다시 읽는다. 화면이 보일 때마다 부른다(다른 사람이 나를 넣었거나, 프로젝트에서 돌아왔을 수 있다).
     * 이미 읽는 중이면 그 결과를 기다린다. 목록이 있으면 돌아가는 표시 없이 조용히 바꾼다.
     */
    fun load() {
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = it.projects.isEmpty(), loadError = null) }
        loadJob = viewModelScope.launch {
            try {
                val loaded = api.listProjects()
                val ids = loaded.map { it.id }.toSet()
                val merged = mergeProjects(loaded, created)
                created = created.filter { it.id !in ids }
                _state.update { it.copy(loading = false, projects = merged, loadError = null) }
            } catch (e: CancellationException) {
                throw e
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
                val summary = ProjectSummary(id = id, title = trimmed, role = Role.OWNER)
                created = created + summary
                // 만들었으면 server에 닿은 것이니 앞서 난 불러오기 오류도 지운다.
                _state.update {
                    it.copy(
                        creating = false,
                        createError = null,
                        loadError = null,
                        projects = mergeProjects(it.projects, listOf(summary)),
                    )
                }
                onCreated(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, createError = userMessage(e)) }
            }
        }
    }
}
