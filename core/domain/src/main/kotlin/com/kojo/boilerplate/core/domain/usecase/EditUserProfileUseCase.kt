package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.ProfileEditOutcome
import com.kojo.boilerplate.core.domain.model.ProfileEditViolation
import com.kojo.boilerplate.core.domain.model.ProfileEditViolation.Companion.MAX_DISPLAY_NAME_LENGTH
import com.kojo.boilerplate.core.domain.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Applies one person's edit of their own profile to the locally held row, and says what became
 * of it.
 *
 * Green step of the kata in `docs/tdd.md`: the shortest thing that makes the spec pass. The
 * four decisions are all here and all inline, which is what the refactor step is for.
 *
 * ### Why this is a use case and not the edit screen's business
 *
 * Every one of the four would otherwise be answered by whichever screen happened to submit the
 * edit, and the two-pane profile layout already proved that a second screen submitting the same
 * thing gets the same policy written out a second time — that is `docs/solid.md` finding 1, and
 * [ObserveUserProfileUseCase] is the read half of its repair.
 *
 * It also gives [UserRepository.saveUser] a caller that is not a test. That method has had none
 * for the life of the repository, which is half of finding 6, and a write path nothing calls is
 * a write path whose policy nobody has had to decide.
 */
class EditUserProfileUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {

    /**
     * @param displayName and [avatarUrl] as the person typed them. Normalising is this use
     *   case's job, not the text field's — see the class KDoc.
     * @param avatarUrl `null` or blank both mean "no avatar"; they are the same intention and
     *   are stored the same way.
     */
    suspend operator fun invoke(
        userId: String,
        displayName: String,
        avatarUrl: String?,
    ): ProfileEditOutcome {
        val editedName = displayName.trim().replace(WHITESPACE_RUN, " ")
        val editedAvatarUrl = avatarUrl?.trim()?.takeIf(String::isNotEmpty)

        val violations = LinkedHashSet<ProfileEditViolation>()
        if (editedName.isEmpty()) {
            violations += ProfileEditViolation.DISPLAY_NAME_BLANK
        }
        if (editedName.length > MAX_DISPLAY_NAME_LENGTH) {
            violations += ProfileEditViolation.DISPLAY_NAME_TOO_LONG
        }
        if (editedAvatarUrl != null && !editedAvatarUrl.startsWith(HTTPS_SCHEME)) {
            violations += ProfileEditViolation.AVATAR_URL_NOT_HTTPS
        }
        if (violations.isNotEmpty()) {
            return ProfileEditOutcome.Rejected(violations)
        }

        val stored = userRepository.getUser(userId).first()
            ?: return ProfileEditOutcome.UnknownUser(userId)

        val edited = stored.copy(displayName = editedName, avatarUrl = editedAvatarUrl)
        if (edited == stored) {
            return ProfileEditOutcome.Unchanged(stored)
        }

        userRepository.saveUser(edited)
        return ProfileEditOutcome.Saved(edited)
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
        const val HTTPS_SCHEME = "https://"
    }
}
