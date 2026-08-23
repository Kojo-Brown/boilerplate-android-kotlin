package com.kojo.boilerplate.feature.profile

import com.kojo.boilerplate.core.domain.model.UserProfile

/**
 * Renders a [UserProfile] as the state the profile screens draw.
 *
 * The split between this and [com.kojo.boilerplate.core.domain.usecase.ObserveUserProfileUseCase]
 * is the whole point of the use-case layer, so it is worth being precise about which half owns
 * what. The use case decides *that* a missing row is a failed load rather than an empty state —
 * a product decision, and the one `docs/solid.md` finding 1 found written down twice. This
 * decides how to *say* it, which is presentation and belongs here next to the strings.
 *
 * Shared by `ProfileViewModel` and `ProfileDetailPaneViewModel` rather than written out in
 * each. The two screens differ in where their user id comes from and in nothing else, so a
 * second copy of this `when` would be the same duplication one layer down.
 */
internal fun UserProfile.toUiState(): ProfileUiState = when (this) {
    is UserProfile.Loaded -> ProfileUiState.Success(
        profile = ProfileData(
            userId = user.id,
            displayName = user.displayName,
            email = user.email,
            avatarUrl = user.avatarUrl,
        ),
    )

    is UserProfile.Missing -> ProfileUiState.Error(message = "User $userId not found")

    is UserProfile.Unavailable -> ProfileUiState.Error(
        message = cause.message ?: "Failed to load profile",
    )
}
