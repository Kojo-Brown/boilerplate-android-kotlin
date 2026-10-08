package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.ProfileEditOutcome
import com.kojo.boilerplate.core.domain.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Red step of the kata in `docs/tdd.md`.
 *
 * This is the skeleton the spec was written against: it reads the row and reports on it, and
 * does none of the three things the tests ask for — no normalising, no validating, no write.
 * Fifteen tests in `EditUserProfileUseCaseTest` describe the behaviour; the commit that
 * introduces this one is the commit where they are red.
 */
class EditUserProfileUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {

    suspend operator fun invoke(
        userId: String,
        displayName: String,
        avatarUrl: String?,
    ): ProfileEditOutcome {
        val stored = userRepository.getUser(userId).first()
            ?: return ProfileEditOutcome.UnknownUser(userId)

        return ProfileEditOutcome.Unchanged(stored)
    }
}
