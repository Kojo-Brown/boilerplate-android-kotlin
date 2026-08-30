package com.kojo.boilerplate.core.network.api

import com.kojo.boilerplate.core.network.model.UserDto
import retrofit2.http.GET
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
}
