package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.ProfileEditOutcome
import com.kojo.boilerplate.core.domain.model.ProfileEditViolation
import com.kojo.boilerplate.core.domain.model.ProfileEditViolation.Companion.MAX_DISPLAY_NAME_LENGTH
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.testing.FakeUserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The spec for [EditUserProfileUseCase], written before it existed. `docs/tdd.md` records the
 * three steps and what each one's run printed.
 *
 * Every test here is about a decision rather than about plumbing, which is what makes the file
 * worth reading as the use case's documentation: what normalisation does to an edit *before*
 * anything else looks at it, which rules reject one, what "nothing changed" means once
 * normalisation has had its say, and the one case where a valid edit still cannot be applied.
 *
 * `savedUsers` on the fake is how "and writes nothing" is asserted. Reading the rows back
 * cannot do it: a rejected or unchanged edit leaves the store holding exactly what it held
 * before, which is also what it would hold if the write had happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditUserProfileUseCaseTest {

    private val ada = User(
        id = "user-1",
        displayName = "Ada Lovelace",
        email = "ada@example.com",
        avatarUrl = "https://example.com/ada.png",
    )

    private val repository = FakeUserRepository(listOf(ada))
    private val editProfile = EditUserProfileUseCase(repository)

    @Test
    fun `a well-formed edit is written and reported as saved`() = runTest {
        val outcome = editProfile(ada.id, displayName = "Ada King", avatarUrl = "https://example.com/king.png")

        val expected = ada.copy(displayName = "Ada King", avatarUrl = "https://example.com/king.png")
        assertEquals(ProfileEditOutcome.Saved(expected), outcome)
        assertEquals(listOf(expected), repository.savedUsers)
        assertEquals(expected, repository.getUser(ada.id).first())
    }

    /**
     * The email is not a parameter, so the stored one has to survive the write. Changing an
     * address is a verification flow rather than a profile edit, and a use case that took one
     * would be the place that forgot to verify it.
     */
    @Test
    fun `the stored email is carried through untouched`() = runTest {
        val outcome = editProfile(ada.id, displayName = "Ada King", avatarUrl = ada.avatarUrl)

        assertEquals(ada.email, (outcome as ProfileEditOutcome.Saved).user.email)
        assertEquals(ada.email, repository.getUser(ada.id).first()?.email)
    }

    @Test
    fun `surrounding whitespace is removed from the display name`() = runTest {
        val outcome = editProfile(ada.id, displayName = "  Ada King\t", avatarUrl = ada.avatarUrl)

        assertEquals("Ada King", (outcome as ProfileEditOutcome.Saved).user.displayName)
    }

    /**
     * The sharp end of normalisation. `"Ada  King"` and `"Ada King"` are the same name to
     * everyone who reads it, and marking `DISPLAY_NAME` locally changed is irreversible on this
     * device — the pending set only ever unions on a local edit — so letting a double space
     * through costs this row the server's value for that field for good.
     */
    @Test
    fun `a run of whitespace inside the display name is collapsed to one space`() = runTest {
        val outcome = editProfile(ada.id, displayName = "Ada   King", avatarUrl = ada.avatarUrl)

        assertEquals("Ada King", (outcome as ProfileEditOutcome.Saved).user.displayName)
    }

    @Test
    fun `surrounding whitespace is removed from the avatar url`() = runTest {
        val outcome = editProfile(ada.id, displayName = ada.displayName, avatarUrl = " https://example.com/king.png ")

        assertEquals("https://example.com/king.png", (outcome as ProfileEditOutcome.Saved).user.avatarUrl)
    }

    /**
     * An empty text field and no avatar are the same intention, and only one of them is a value
     * the rest of the app can reason about: `avatarUrl` is nullable precisely so that "no
     * avatar" has one representation, and `""` would be a second one that every reader has to
     * remember to check for.
     */
    @Test
    fun `an avatar url of blank text is stored as no avatar at all`() = runTest {
        val outcome = editProfile(ada.id, displayName = ada.displayName, avatarUrl = "   ")

        assertEquals(ProfileEditOutcome.Saved(ada.copy(avatarUrl = null)), outcome)
    }

    @Test
    fun `an edit whose normalised form equals the stored row is unchanged and is not written`() = runTest {
        val outcome = editProfile(ada.id, displayName = "  Ada Lovelace  ", avatarUrl = ada.avatarUrl)

        assertEquals(ProfileEditOutcome.Unchanged(ada), outcome)
        assertEquals(emptyList<User>(), repository.savedUsers)
    }

    @Test
    fun `a display name that is blank once normalised is rejected`() = runTest {
        val outcome = editProfile(ada.id, displayName = " \t ", avatarUrl = ada.avatarUrl)

        assertEquals(
            ProfileEditOutcome.Rejected(setOf(ProfileEditViolation.DISPLAY_NAME_BLANK)),
            outcome,
        )
    }

    @Test
    fun `a display name longer than the limit is rejected`() = runTest {
        val outcome = editProfile(
            ada.id,
            displayName = "a".repeat(MAX_DISPLAY_NAME_LENGTH + 1),
            avatarUrl = ada.avatarUrl,
        )

        assertEquals(
            ProfileEditOutcome.Rejected(setOf(ProfileEditViolation.DISPLAY_NAME_TOO_LONG)),
            outcome,
        )
    }

    /**
     * The boundary, from both sides, and measured *after* normalising — so the limit is a limit
     * on the name rather than on how carelessly it was typed.
     */
    @Test
    fun `a name at the limit is accepted, and padding it does not push it over`() = runTest {
        val atTheLimit = "a".repeat(MAX_DISPLAY_NAME_LENGTH)

        val outcome = editProfile(ada.id, displayName = "   $atTheLimit   ", avatarUrl = ada.avatarUrl)

        assertEquals(atTheLimit, (outcome as ProfileEditOutcome.Saved).user.displayName)
    }

    @Test
    fun `an avatar url that is not https is rejected`() = runTest {
        val outcome = editProfile(ada.id, displayName = ada.displayName, avatarUrl = "http://example.com/king.png")

        assertEquals(
            ProfileEditOutcome.Rejected(setOf(ProfileEditViolation.AVATAR_URL_NOT_HTTPS)),
            outcome,
        )
    }

    /**
     * Every violation, not the first one. A form that reports one error per submission makes the
     * person submit once per mistake, and the ordering of the checks — an implementation detail
     * — decides which mistake they are told about first.
     */
    @Test
    fun `every violation an edit commits is reported in one answer`() = runTest {
        val outcome = editProfile(ada.id, displayName = "", avatarUrl = "ftp://example.com/king.png")

        assertEquals(
            ProfileEditOutcome.Rejected(
                setOf(
                    ProfileEditViolation.DISPLAY_NAME_BLANK,
                    ProfileEditViolation.AVATAR_URL_NOT_HTTPS,
                ),
            ),
            outcome,
        )
    }

    @Test
    fun `a rejected edit writes nothing`() = runTest {
        editProfile(ada.id, displayName = "", avatarUrl = ada.avatarUrl)

        assertEquals(emptyList<User>(), repository.savedUsers)
        assertEquals(ada, repository.getUser(ada.id).first())
    }

    /**
     * The case with real consequences. `UserRepository.saveUser` records *which* fields this
     * client changed, and against a row that is not there it can only answer "all of them" —
     * so a write for an unknown id does not create a draft, it creates a row claiming the user
     * locally authored every field of it, with an idempotency key, which the next background
     * sync pushes to the server as a deliberate whole-profile mutation. Reporting the id as
     * unknown is the only answer that does not invent that edit.
     */
    @Test
    fun `a valid edit for an id the app does not hold is reported unknown and writes nothing`() = runTest {
        val outcome = editProfile("user-404", displayName = "Grace Hopper", avatarUrl = null)

        assertEquals(ProfileEditOutcome.UnknownUser("user-404"), outcome)
        assertEquals(emptyList<User>(), repository.savedUsers)
    }

    /**
     * Validation comes first, so it cannot be affected by what the store holds or by whether the
     * store can be read at all. The fake is rigged to throw from `getUser`: an implementation
     * that read the row before validating would surface that instead of the violation the person
     * can actually act on.
     */
    @Test
    fun `validation does not read the store`() = runTest {
        repository.shouldThrowOnGetUser = IllegalStateException("the store must not be read")

        val outcome = editProfile(ada.id, displayName = "", avatarUrl = null)

        assertEquals(
            ProfileEditOutcome.Rejected(setOf(ProfileEditViolation.DISPLAY_NAME_BLANK)),
            outcome,
        )
    }
}
