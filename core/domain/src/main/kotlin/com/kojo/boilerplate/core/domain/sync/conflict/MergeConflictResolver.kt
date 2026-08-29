package com.kojo.boilerplate.core.domain.sync.conflict

import javax.inject.Inject

/**
 * [ConflictPolicy.MERGE]: the fetched row becomes the new base, and this client's unpushed
 * fields are laid back over it.
 *
 * ## The shape of the merge
 *
 * Field by field, over [VersionedUser.locallyChanged] and nothing else:
 *
 * - a field this client edited keeps the local value;
 * - every other field takes the server's value, including fields that differ for reasons this
 *   client knows nothing about.
 *
 * The version that results is the server's. That is not cosmetic: it is what makes the merge
 * *terminate*. Keeping the local version would leave the row permanently behind the server, so
 * every subsequent fetch would arrive as a fresh conflict and re-run the same merge forever.
 * Taking the server's version records that this row has seen it, and the next fetch of an
 * unchanged row is a [ConflictResolution.KeepLocal] rather than a merge.
 *
 * ## Why a converged field stops being pending
 *
 * A field is in [VersionedUser.locallyChanged] because this client changed it and the server
 * had not acknowledged it. If the fetched row now carries the same value, the server *has* —
 * whether because the edit was pushed, or because someone else made the same change, and this
 * client cannot tell the two apart and does not need to. Either way the field is no longer
 * divergent, and leaving it marked would mean re-applying a value identical to the server's on
 * every future sync, forever, over a row nothing is actually holding.
 *
 * When that clears the last pending field the row becomes clean, and the merge collapses to
 * taking the remote row exactly — which is the correct answer and is written that way below
 * rather than being arrived at by re-applying fields onto themselves.
 *
 * ## What it does not do
 *
 * Push. Nothing here sends the local edit anywhere, so a field that stays divergent stays
 * divergent: it beats the server's value on this device, on every sync, until an outbox pushes
 * it and the server's next version carries it back. Merging is the read half of a
 * bidirectional sync, and it is only half — see `docs/conflict-resolution.md`.
 *
 * It also does not merge *within* a field. Two clients editing one display name still produce
 * a winner rather than a combination; a character-level merge needs an operational transform
 * or a CRDT, which is a different class of machinery and a different data model.
 */
class MergeConflictResolver @Inject constructor() : VersionOrderedConflictResolver() {

    override val policy: ConflictPolicy = ConflictPolicy.MERGE

    override fun reconcile(
        local: VersionedUser,
        remote: VersionedUser,
    ): ConflictResolution {
        val stillDiverged = local.locallyChanged
            .filterTo(LinkedHashSet()) { it.differs(local.user, remote.user) }

        if (stillDiverged.isEmpty()) {
            return ConflictResolution.Write(remote)
        }

        val merged = stillDiverged.fold(remote.user) { user, field ->
            field.copyInto(target = user, source = local.user)
        }

        return ConflictResolution.Write(
            VersionedUser(
                user = merged,
                version = remote.version,
                locallyChanged = stillDiverged,
            ),
        )
    }
}
