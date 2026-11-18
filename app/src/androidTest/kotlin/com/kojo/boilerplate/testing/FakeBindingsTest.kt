package com.kojo.boilerplate.testing

import com.kojo.boilerplate.core.database.AppDatabase
import com.kojo.boilerplate.core.database.dao.UserDao
import com.kojo.boilerplate.core.database.dao.UserPagingDao
import com.kojo.boilerplate.core.database.entity.UserEntity
import com.kojo.boilerplate.core.domain.model.PushOutcome
import com.kojo.boilerplate.core.domain.model.User
import com.kojo.boilerplate.core.domain.repository.PendingUserChangeRepository
import com.kojo.boilerplate.core.domain.repository.UserRepository
import com.kojo.boilerplate.core.domain.sync.conflict.UserField
import com.kojo.boilerplate.core.network.api.AuthApi
import com.kojo.boilerplate.core.network.api.UserApi
import com.kojo.boilerplate.core.network.model.LoginRequest
import com.kojo.boilerplate.core.network.model.UserDto
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The first test in this repository to build the application's Hilt graph on a device, and what
 * [TestNetworkModule] and [TestDatabaseModule] actually buy.
 *
 * ## What each half of the suite is for
 *
 * Two of the tests assert the *replacement* — that the `UserApi` and `AuthApi` the graph hands
 * out are the doubles, and that the database has no file behind it. Those are cheap and they are
 * the ones that fail loudly if a `@TestInstallIn` stops being processed, which is the failure
 * mode worth guarding: a replacement module compiled without the KSP processor in
 * `kspAndroidTest` contributes nothing and leaves the suite running against the real
 * `NetworkModule`, where every test either hangs on a socket or passes for the wrong reason.
 *
 * The rest assert *through* the replacement. `UserRepositoryImpl`, its three decorators,
 * `ResolvingUserWriter`, `MergeConflictResolver`, `UuidIdempotencyKeyGenerator` and the generated
 * Room DAOs are all the production classes, wired by the production modules, over two fakes at
 * the edges — so what they exercise is the real read-modify-write path, which no unit test in
 * `:data` can cover end to end because its DAO is a `MutableMap` and its API is a mock.
 *
 * ## Why these run in `runBlocking` and not `runTest`
 *
 * `runTest` controls virtual time for the coroutine it starts, and nothing here is confined to
 * that coroutine: `CachingUserRepository` dispatches the fetch into the injected
 * `@ApplicationScope`, the repository hops to `@IoDispatcher`, and Room runs its queries on its
 * own executor. Virtual time buys nothing over work it does not schedule, and `advanceUntilIdle`
 * on a scheduler none of it uses is the kind of line that makes a test look deterministic
 * without being so. Real suspension over real dispatchers is what this configuration is for.
 *
 * ## Isolation is pinned, not assumed
 *
 * Hilt builds a fresh component for each test method, so each gets its own empty in-memory
 * database and its own [TestUserApi]. That is not left as a claim: more than one test below
 * asserts the *exact* list of calls that reached the double, and a shared component would make
 * those lists accumulate across methods and fail whichever ran second.
 */
@HiltAndroidTest
class FakeBindingsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var userApi: UserApi

    @Inject
    lateinit var doubleUserApi: TestUserApi

    @Inject
    lateinit var authApi: AuthApi

    @Inject
    lateinit var doubleAuthApi: TestAuthApi

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var userDao: UserDao

    @Inject
    lateinit var userPagingDao: UserPagingDao

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var pendingUserChanges: PendingUserChangeRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        // The in-memory database is this test's singleton and nothing else will close it; see
        // TestDatabaseModule's KDoc on lifetime.
        database.close()
    }

    @Test
    fun theApiBindingsAreTheInMemoryDoubles() {
        // `assertSame` and not `assertTrue(… is TestUserApi)`: the doubles are bound unscoped
        // over a `@Singleton` implementation, and identity is what says a test and the code under
        // test are looking at one object rather than two with the same class.
        assertSame(doubleUserApi, userApi)
        assertSame(doubleAuthApi, authApi)
    }

    @Test
    fun theDatabaseBindingHasNoFileBehindIt() {
        // The deterministic form of "nothing was written to disk". `Room.inMemoryDatabaseBuilder`
        // leaves the open helper's database name null, and the production binding names
        // `boilerplate.db` — so this fails if `DatabaseModule` is ever the one in the graph.
        // Asserting on the absence of the file itself would be weaker as well as flakier: a
        // leftover file from an earlier install says nothing about which binding is in use.
        assertNull(database.openHelper.databaseName)
    }

    @Test
    fun bothDaosAreViewsOfTheSameDatabase() = runBlocking {
        // `DatabaseModule` provides two DAOs over one `AppDatabase`, and `UserPagingDao` exists
        // *because* a page's rows and its cursor have to be committed together — so a replacement
        // handing out two databases would break `commitPage`'s whole reason for existing while
        // every statement still parsed.
        userPagingDao.upsertUser(SEEDED.asLocalRow())

        assertEquals(SEEDED.displayName, userDao.findById(SEEDED.id)?.displayName)
    }

    @Test
    fun syncUserFetchesFromTheDoubleAndStoresTheRow() = runBlocking {
        doubleUserApi.seed(SEEDED)

        val synced = userRepository.syncUser(SEEDED.id)

        assertEquals(SEEDED.displayName, synced.getOrNull()?.displayName)
        assertEquals(listOf(TestUserApi.Call.SingleUser(SEEDED.id)), doubleUserApi.calls)

        val stored = requireNotNull(userDao.findById(SEEDED.id)) { "the fetched row was not stored" }
        assertEquals(SEEDED.email, stored.email)
        // The version came off the wire and through conflict resolution rather than being
        // defaulted, which is what makes the next fetch orderable against this row.
        assertEquals(SEEDED.version, stored.version)
        assertEquals(emptySet<UserField>(), stored.locallyChanged)
    }

    @Test
    fun aTerminalFailureFromTheDoubleIsNotRetriedAndWritesNothing() = runBlocking {
        doubleUserApi.seed(SEEDED)
        // Terminal rather than transient on purpose: `isTransientFailure` reads an `IOException`
        // or a retryable `HttpException` as worth another attempt, and `RetryingUserRepository`
        // would then make three more calls with real backoff between them. What is being
        // asserted here is that exactly one request reached the double, so the exception has to
        // be one the retry policy declines.
        doubleUserApi.failure = IllegalStateException("the test double was told to fail")

        val synced = userRepository.syncUser(SEEDED.id)

        assertTrue(synced.isFailure)
        assertEquals(1, doubleUserApi.calls.size)
        assertNull(userDao.findById(SEEDED.id))
    }

    @Test
    fun aPendingEditIsPushedUnderTheKeyTheDatabaseHolds() = runBlocking {
        doubleUserApi.seed(SEEDED)
        userRepository.syncUser(SEEDED.id)
        userRepository.saveUser(EDITED)

        val edited = requireNotNull(userDao.findById(EDITED.id)) { "the local edit was not stored" }
        val key = requireNotNull(edited.pendingChangeKey) { "a dirty row must carry a key" }
        assertEquals(setOf(UserField.DISPLAY_NAME), edited.locallyChanged)

        val outcome = pendingUserChanges.pushPendingChanges()

        assertEquals(PushOutcome(pushed = 1, failed = 0), outcome)
        // The key is read from the row rather than minted at send time — the one property that
        // makes the header worth sending at all, and the one a mocked API cannot show, because
        // the row it has to match is in the database.
        val update = doubleUserApi.calls.filterIsInstance<TestUserApi.Call.Update>().single()
        assertEquals(key, update.idempotencyKey)
        assertEquals(EDITED.displayName, doubleUserApi.serverCopyOf(EDITED.id)?.displayName)
        // Acknowledged: the row is no longer in the push queue and has given up its key.
        assertTrue(userDao.findPendingChanges().isEmpty())
        assertNull(userDao.findById(EDITED.id)?.pendingChangeKey)
    }

    @Test
    fun signingInReachesTheDoubleAndNoCredentialIsRecorded() = runBlocking {
        val tokens = authApi.login(LoginRequest(email = ACCOUNT_EMAIL, password = NOT_A_PASSWORD))

        assertEquals(TestAuthApi.ACCESS_TOKEN, tokens.accessToken)
        assertEquals(listOf(TestAuthApi.Call.Login(ACCOUNT_EMAIL)), doubleAuthApi.calls)
    }

    private fun UserDto.asLocalRow(): UserEntity = UserEntity(
        id = id,
        displayName = displayName,
        email = email,
        avatarUrl = avatarUrl,
        version = version,
    )

    private companion object {

        /** The server's copy. Version 3 rather than 0 so the row is orderable against a push. */
        val SEEDED = UserDto(
            id = TestUserApi.DEFAULT_CURRENT_USER_ID,
            displayName = "Seeded Name",
            email = "seeded@example.test",
            version = 3L,
        )

        /** The same user with one field edited locally, as an edit screen would hand it over. */
        val EDITED = User(
            id = SEEDED.id,
            displayName = "Edited Name",
            email = SEEDED.email,
            avatarUrl = SEEDED.avatarUrl,
        )

        const val ACCOUNT_EMAIL = "someone@example.test"

        /** Obviously not a credential, which is the only kind of password a fixture may hold. */
        const val NOT_A_PASSWORD = "not-a-real-password"
    }
}
