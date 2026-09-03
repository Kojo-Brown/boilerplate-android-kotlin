package com.kojo.boilerplate.core.network.api

import com.kojo.boilerplate.core.network.model.UpdateUserRequest
import com.kojo.boilerplate.core.network.model.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

interface UserApi {

    @GET("users/me")
    suspend fun getCurrentUser(): UserDto

    @GET("users/{id}")
    suspend fun getUser(@Path("id") id: String): UserDto

    /**
     * One page of the user list, 1-indexed.
     *
     * ## Why the response is a bare list and not an envelope
     *
     * A `{ "items": [...], "total_pages": 12 }` envelope would let the client know where the
     * end is before it reaches it. This deliberately does not model one, because the client
     * does not need it: a page shorter than [perPage] is the end, which is true of every
     * page-number API whether or not it also reports a total, and it is one fewer field for a
     * server to get wrong. `UsersRemoteMediator` reads end-of-pagination that way.
     *
     * The trade-off is that the last *full* page costs one extra empty request to discover.
     * That is a single round trip at the bottom of the list, against an envelope field that
     * would have to be believed.
     *
     * @param page 1-based. Page 1 is the first page; there is nothing before it, which is why
     *   the mediator answers `PREPEND` without a request.
     * @param perPage how many users to return. The mediator passes the `PagingConfig` page
     *   size, so a server that returns fewer *because it caps the page size* is independently
     *   correct here and simply reads as the end of the list — see `docs/paging.md`.
     */
    @GET("users")
    suspend fun getUsers(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int,
    ): List<UserDto>

    /**
     * Applies this client's pending edit to a user, at most once however many times it is sent.
     *
     * ## The header is the contract
     *
     * `Idempotency-Key` is the de-facto standard name for this — Stripe's, and what most APIs
     * that offer the guarantee call it — and the guarantee it asks for is: *if you have seen
     * this key before, do not apply the change again; return what you returned the first time.*
     * The client's side of the bargain is that the key names one mutation for its whole life,
     * across processes and across days, which is why [idempotencyKey] is read from the row
     * rather than generated at the call site. See `docs/idempotency.md`.
     *
     * Against a server that ignores the header this degrades to an ordinary `PATCH`: correct
     * while nothing is retried, and no worse than the app was before the header existed. That
     * is worth stating because it is the only failure mode this client cannot detect —
     * a server that does not dedupe answers exactly like one that does.
     *
     * ## Why `PATCH` and not `PUT`
     *
     * The body carries the fields this client changed, not a whole user. A `PUT` promises the
     * representation is complete, and this client's copy of the fields it did *not* edit may be
     * stale — sending them back is how a background sync silently reverts someone else's change
     * to the same row.
     *
     * ## Why the response is a full [UserDto]
     *
     * It is what the row now is, server-side, including the version the write assigned it. That
     * makes the acknowledgement a fetch as well as a confirmation: the caller commits it
     * through the same conflict resolution as any other response, so a row whose pending set
     * has moved on since the request was sent is reconciled rather than overwritten.
     */
    @PATCH("users/{id}")
    suspend fun updateUser(
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body update: UpdateUserRequest,
    ): UserDto
}
