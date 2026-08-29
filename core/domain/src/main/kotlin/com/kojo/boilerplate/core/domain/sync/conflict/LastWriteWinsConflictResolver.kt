package com.kojo.boilerplate.core.domain.sync.conflict

import javax.inject.Inject

/**
 * [ConflictPolicy.LAST_WRITE_WINS]: the newer version replaces the older one, whole.
 *
 * ## "Last write" means the highest version, not the latest arrival
 *
 * The name is the standard one and it is misleading in the way that matters, so it is worth
 * being explicit: this does not compare timestamps and it does not prefer whichever write
 * happened most recently in wall-clock terms. It prefers the higher
 * [VersionedUser.version], which is the server's own statement of order.
 *
 * That distinction is the whole reason there is no clock in this file. A wall-clock
 * last-write-wins on a mobile client compares a server timestamp against a device one, and a
 * device whose clock is ten minutes fast then wins every conflict it takes part in — including
 * the ones where its data is older. The user does not see an error, because nothing failed;
 * they see the server's data quietly refuse to arrive until the skew is corrected. A
 * monotonic counter assigned by one writer has no such failure mode.
 *
 * ## What it costs
 *
 * [reconcile] is only reached when this client is holding an edit the server has not seen,
 * and this policy discards it. That is the trade in one line: no merge logic, no per-field
 * bookkeeping to get wrong, and a user's unsaved change can vanish because an unrelated field
 * of the same row changed on the server.
 */
class LastWriteWinsConflictResolver @Inject constructor() : VersionOrderedConflictResolver() {

    override val policy: ConflictPolicy = ConflictPolicy.LAST_WRITE_WINS

    /**
     * The local edit is dropped, and dropped *silently* — that is what this policy is.
     *
     * The resulting row carries no [VersionedUser.locallyChanged], because [remote] came off
     * the network and nothing in it is pending. A caller that wants the user told about the
     * discarded edit has picked the wrong policy: the information needed to describe what was
     * lost is exactly what [ConflictPolicy.MERGE] keeps.
     */
    override fun reconcile(
        local: VersionedUser,
        remote: VersionedUser,
    ): ConflictResolution = ConflictResolution.Write(remote)
}
