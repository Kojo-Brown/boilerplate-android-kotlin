package com.kojo.boilerplate.core.domain.sync.conflict

/**
 * Decides what the local store should hold when a fetched row meets one that is already there.
 *
 * ## What this replaces
 *
 * An unconditional upsert. Every fetch in `UserRepositoryImpl` wrote its response straight over
 * whatever was in Room, which is correct exactly while the network is the only writer and every
 * response arrives in the order it was sent. Neither holds here. `syncUsers` fans out
 * concurrently, so two responses for the same id race; `CachingUserRepository` coalesces
 * callers onto one in-flight request and hands the same response to all of them; and
 * `saveUser` writes to the same rows from the app. The result was decided by arrival order,
 * which is not a policy — it is the absence of one.
 *
 * ## Why the policies share the ordering and differ only at the end
 *
 * [VersionOrderedConflictResolver] below implements four of the five cases, because there is
 * only one defensible answer to each of them and duplicating that answer per policy is how the
 * two drift apart. A policy exists to answer the fifth: **the fetched row is genuinely newer,
 * and this client is holding an edit the server has not seen.** Everything else — no row yet,
 * a stale response, a version already stored, a clean fast-forward — is bookkeeping.
 *
 * Implementations are pure functions of their two arguments: no clock, no I/O, no suspension.
 * That is what lets the write they inform run inside a database transaction without holding
 * one open across anything that can block.
 */
interface ConflictResolver {

    /**
     * The policy this resolver implements.
     *
     * Redundant with the branch that selects it in `ConflictResolverModule`, and deliberately
     * so — the same reason `SyncStrategy.mode` exists. Nothing in Dagger checks that a
     * `@Provides` returning a `ConflictResolver` returns the one the branch names, and a
     * resolver that reports a policy it does not implement is a lie a test can catch.
     */
    val policy: ConflictPolicy

    /**
     * @param local what the store holds for this row, or `null` if it holds nothing.
     * @param remote the row as just fetched. Always [VersionedUser.locallyChanged]-empty:
     *   nothing off the network carries this client's pending edits.
     */
    fun resolve(local: VersionedUser?, remote: VersionedUser): ConflictResolution
}

/**
 * The four cases every policy agrees on, with the fifth left to [reconcile].
 *
 * The order of the branches is the argument. Each one is only reachable because the ones above
 * it did not fire, which is what lets [reconcile] be specified so narrowly: by the time it is
 * called, `remote.version > local.version` and `local.hasLocalEdits` are both established.
 *
 * ## What happens against a server that does not send a version
 *
 * Every row sits at version `0`, so no fetch is ever strictly newer than what is stored, and
 * only the third branch can fire. That degrades to "the latest response wins" — which is
 * exactly what this repository did before there was a version column, so an unversioned
 * backend is no worse off than it was — with the one improvement that a row holding an
 * unpushed local edit is still protected, because the fourth branch does not depend on the
 * version having moved. Versions buy ordering; the dirty set buys the edit. They are worth
 * having separately.
 */
abstract class VersionOrderedConflictResolver : ConflictResolver {

    final override fun resolve(
        local: VersionedUser?,
        remote: VersionedUser,
    ): ConflictResolution = when {
        // Nothing to conflict with. The first fetch of a row is always taken.
        local == null -> ConflictResolution.Write(remote)

        // A response older than what is stored. This is the out-of-order case, and it is not
        // hypothetical: a fan-out over twenty ids issues twenty concurrent requests, and
        // nothing makes the slow one carry the newer row. Writing it would move the store
        // backwards, and the next refresh would move it forwards again — a row that flickers
        // between two values with no user action behind it.
        remote.version < local.version -> ConflictResolution.KeepLocal

        // Nothing of this client's to lose, so the server's row is simply taken — unless it
        // is, to the byte, what is already stored. Recognising that is what keeps a refresh
        // that changed nothing from invalidating the queries observing this table; see
        // ConflictResolution.KeepLocal. Both halves have to match: identical fields under a
        // higher version still need writing, because the version is what a later response is
        // ordered against.
        !local.hasLocalEdits ->
            if (local.version == remote.version && local.user == remote.user) {
                ConflictResolution.KeepLocal
            } else {
                ConflictResolution.Write(remote)
            }

        // A pending edit, and a server that has not moved since this row was fetched. Whatever
        // the two disagree about is therefore this client's own unpushed change, and taking
        // the server's copy of it is how an edit gets undone by the refresh that was meant to
        // confirm it. Both policies agree here: there is no newer server state to prefer.
        remote.version == local.version -> ConflictResolution.KeepLocal

        else -> reconcile(local, remote)
    }

    /**
     * The conflict proper.
     *
     * @param local the stored row. Guaranteed [VersionedUser.hasLocalEdits], and guaranteed to
     *   carry a lower [VersionedUser.version] than [remote].
     * @param remote the fetched row, strictly newer than [local].
     */
    protected abstract fun reconcile(
        local: VersionedUser,
        remote: VersionedUser,
    ): ConflictResolution
}
