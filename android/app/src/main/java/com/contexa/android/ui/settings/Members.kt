package com.contexa.android.ui.settings

import com.contexa.android.api.Member
import com.contexa.android.api.Role
import com.contexa.android.ui.projects.roleLabel

/*
 * 구성원 화면의 규칙. 화면과 떼어 두고 테스트한다.
 * 권한 판단은 server가 한다(identity/IdentityAccess.setMember). 여기서는 버튼을 보일지만 정한다.
 */

/** 구성원 목록에서 나의 역할. 목록을 아직 못 읽었으면 null. */
fun myRole(members: List<Member>): Role? = members.firstOrNull { it.me }?.role

/** 검토함에서 확인·정정할 수 있는 역할. */
fun canReview(role: Role?): Boolean = role == Role.OWNER || role == Role.REVIEWER

/** 구성원을 넣고 역할을 바꿀 수 있는 역할. */
fun canManageMembers(role: Role?): Boolean = role == Role.OWNER

/** 넣을 수 있는 역할. 처음 고른 값은 가장 권한이 적은 구성원이다. */
val INVITE_ROLES: List<Role> = listOf(Role.MEMBER, Role.REVIEWER, Role.OWNER)

/** 역할이 하는 일. 넣기 전에 고를 때 보여 준다. */
fun roleDescription(role: Role): String = when (role) {
    Role.OWNER -> "구성원을 넣고 역할을 바꿀 수 있고, 검토도 합니다."
    Role.REVIEWER -> "AI가 뽑은 후보를 확인·정정합니다."
    Role.MEMBER -> "자료를 올리고 질문하며, 확인된 내용을 봅니다."
}

private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/** 넣기 전에 이메일 칸을 검사한다. 괜찮으면 null. server와 같은 모양을 본다. */
fun inviteError(email: String): String? {
    val trimmed = email.trim()
    return when {
        trimmed.isEmpty() -> "넣을 사람의 이메일을 입력해 주세요."
        !EMAIL.matches(trimmed) -> "이메일 형식이 올바르지 않습니다."
        else -> null
    }
}

/** 이미 구성원인 이메일이면 그 사람. 소유자만 다른 사람의 이메일을 보므로 소유자 화면에서만 맞는다. */
fun memberByEmail(members: List<Member>, email: String): Member? =
    members.firstOrNull { it.email?.equals(email.trim(), ignoreCase = true) == true }

/** 받침에 맞춘 "로"·"으로". 받침이 없거나 ㄹ이면 "로"다. */
internal fun withRo(word: String): String {
    val last = word.lastOrNull() ?: return word
    if (last !in '가'..'힣') return "${word}(으)로"
    val batchim = (last - '가') % 28
    return if (batchim == 0 || batchim == 8) "${word}로" else "${word}으로"
}

/** 넣거나 역할을 바꾼 뒤 알림. name은 목록에 있던 사람이면 이름, 새로 넣었으면 이메일. */
fun memberNotice(name: String, role: Role, existed: Boolean): String {
    val label = withRo(roleLabel(role))
    return if (existed) "$name 님의 역할을 $label 바꿨습니다." else "$name 님을 $label 넣었습니다."
}

/**
 * 이 사람의 역할을 바꿀 수 있나. 소유자가 한 명뿐이면 그 사람은 바꿀 수 없다(server도 받지 않는다).
 * 이메일이 없으면 소유자가 아닌 화면이므로 바꿀 수 없다.
 */
fun canChangeRole(members: List<Member>, member: Member): Boolean =
    member.email != null && !(member.role == Role.OWNER && members.count { it.role == Role.OWNER } == 1)

/** 내 역할을 낮추려는가. 소유자 권한을 잃으므로 한 번 더 묻는다. */
fun losesOwnership(member: Member, role: Role): Boolean = member.me && member.role == Role.OWNER && role != Role.OWNER
