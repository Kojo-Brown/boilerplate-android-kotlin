package com.kojo.boilerplate.core.domain.usecase

import com.kojo.boilerplate.core.domain.model.ProfileEdit
import com.kojo.boilerplate.core.domain.model.ProfileEditOutcome
import com.kojo.boilerplate.core.domain.model.ProfileEditViolation
import com.kojo.boilerplate.core.domain.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Applies one person's edit of their own profile to the locally held row, and says what became
 * of it.
 *
 * Four decisions, and after the refactor step of `docs/tdd.md` the body is those four decisions
 * and their order — [ProfileEdit] owns normalising and [ProfileEditViolation] owns the rules.
 *
 * ### Why this is a use case rather than the edit screen's business
 *
 * Because otherwise each of the four is answered by whichever screen happens to submit the edit,
 * and the two-pane profile layout has already demonstrated what that costs: the same policy
 * written out twice, verbatim, in the layer furthest from the data. That is `docs/solid.md`
 * finding 1, and [ObserveUserProfileUseCase] is the read half of its repair.
 *
 * It also gives [UserRepository.saveUser] its first caller outside a test. The method has had
 * none for the life of the repository — half of finding 6 — and a write path nothing calls is a
 * write path whose policy nobody has had to decide.
 *
 * ### Decision 1: normalising is this layer's job, not the text field's
 *
 * [ProfileEdit.normalising] is the first statement and the only constructor, which is the
 * argument for the type rather than for two `trim()` calls. The failure it prevents is silent and
 * permanent: `saveUser` records which fields this client changed and a local edit only ever adds
 * to that set, so a display name differing from the stored one by a trailing space is a genuine
 * change as far as the write path can tell. It marks `DISPLAY_NAME` locally owned, and from then
 * on `MergeConflictResolver` discards the server's value for that field forever. Nothing throws;
 * the field just stops syncing.
 *
 * ### Decision 2: validation comes before the store is read
 *
 * In that order for three reasons, and the first is the one that matters: a rule measured against
 * the input cannot be influenced by what the store happens to hold, or by whether the store can
 * be read at all. Second, it keeps a round trip off the path for an edit that could never be
 * saved. Third, a field error is the one thing the person looking at the screen can actually fix,
 * and reporting [ProfileEditOutcome.UnknownUser] ahead of it would hand them something they
 * cannot.
 *
 * Every broken rule is reported, not the first — see [ProfileEditViolation.brokenBy].
 *
 * ### Decision 3: an unknown id is refused rather than created
 *
 * This is the decision with real consequences, and it is the one an `upsert`-shaped API invites
 * you to get wrong. `saveUser` has to answer "which fields did this client change?" against the
 * row that is already there, and against a row that is *not* there the only answer it can give is
 * "all of them". So a write for an id this device does not hold would not create a draft: it
 * would create a row claiming the person locally authored every field of it, carrying an
 * idempotency key, which the next background sync pushes to the server as a deliberate
 * whole-profile mutation. Against an id that never existed, or one belonging to somebody else,
 * that is a fabricated edit nobody made.
 *
 * Reachable without anybody doing anything wrong: a deep link opens a profile the app has never
 * cached, so the editable screen is reached before any sync has written the row.
 *
 * ### Decision 4: an edit that changes nothing is not a write
 *
 * Reported as [ProfileEditOutcome.Unchanged], and reported rather than inferred — a screen that
 * had to compare the form against the row to find out would be re-deciding, per screen, what
 * "changed" means, which is Decision 1 handed back to the caller.
 *
 * Writing anyway would be *nearly* harmless: `saveUser` leaves no field pending and mints no key
 * when every value matches. "Nearly" is the whole reason this arm exists — it would still be a
 * database transaction and a Room emission, so every screen observing the row re-renders because
 * somebody opened a form and closed it.
 *
 * ### What is not here
 *
 * No scheduling. The push is `PerformBackgroundSyncUseCase`'s, run by the periodic worker that
 * `BoilerplateApp` already registers, and an edit does not need to ask for it: the pending fields
 * and the idempotency key `saveUser` leaves behind are the whole handover. An immediate
 * sync-on-edit would be a scheduling decision in its own right, and it belongs with the worker's
 * policy rather than inside the write — see `docs/background-sync.md`.
 *
 * No dispatcher. The repository confines its own I/O, so what is left here is a trim, three
 * predicates and one allocation; a `withContext` to cover that costs more than the work. See
 * `docs/dispatchers.md`.
 *
 * Cancellation is untouched, as everywhere else in this codebase: `getUser` and `saveUser`
 * propagate it and nothing here catches.
 */
class EditUserProfileUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {

    /**
     * @param displayName as the person typed it. Normalising is Decision 1, above.
     * @param avatarUrl as the person typed it. `null` and blank are the same intention — no
     *   avatar — and are stored the same way.
     */
    suspend operator fun invoke(
        userId: String,
        displayName: String,
        avatarUrl: String?,
    ): ProfileEditOutcome {
        val edit = ProfileEdit.normalising(displayName, avatarUrl)

        val violations = ProfileEditViolation.brokenBy(edit)
        if (violations.isNotEmpty()) {
            return ProfileEditOutcome.Rejected(violations)
        }

        val stored = userRepository.getUser(userId).first()
            ?: return ProfileEditOutcome.UnknownUser(userId)

        val edited = edit.appliedTo(stored)
        if (edited == stored) {
            return ProfileEditOutcome.Unchanged(stored)
        }

        userRepository.saveUser(edited)
        return ProfileEditOutcome.Saved(edited)
    }
}
