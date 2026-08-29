package com.kojo.boilerplate.core.domain.sync.conflict

import com.kojo.boilerplate.core.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserFieldTest {

    private val ada = User(
        id = "1",
        displayName = "Ada",
        email = "ada@example.com",
        avatarUrl = null,
    )

    /**
     * Every field this enum names is actually reachable through [UserField.copyInto] and
     * [UserField.differs].
     *
     * The failure this catches is copy-paste: `AVATAR_URL` whose `copyInto` writes
     * `displayName` compiles, type-checks, and produces a merge that silently drops one field
     * and duplicates another. Round-tripping each constant on its own is what makes the
     * pairing observable.
     */
    @Test
    fun `each field copies itself and only itself`() {
        val other = User(
            id = "1",
            displayName = "Grace",
            email = "grace@example.com",
            avatarUrl = "https://cdn.example.com/fake-avatar.png",
        )

        UserField.entries.forEach { field ->
            val copied = field.copyInto(target = ada, source = other)

            assertEquals(
                setOf(field),
                UserField.changedBetween(ada, copied),
                "${field.name} changed the wrong set of fields",
            )
        }
    }

    @Test
    fun `identical users differ in nothing`() {
        assertEquals(emptySet<UserField>(), UserField.changedBetween(ada, ada.copy()))
    }

    @Test
    fun `changedBetween names every field that differs`() {
        val other = ada.copy(displayName = "Grace", avatarUrl = "https://cdn.example.com/fake-avatar.png")

        assertEquals(
            setOf(UserField.DISPLAY_NAME, UserField.AVATAR_URL),
            UserField.changedBetween(ada, other),
        )
    }

    /**
     * A field going to `null` is a change. Treating an absent value as "no edit" is how a
     * merge loses a deletion — see `MergeConflictResolverTest`.
     */
    @Test
    fun `clearing a nullable field counts as a difference`() {
        val withAvatar = ada.copy(avatarUrl = "https://cdn.example.com/fake-avatar.png")

        assertEquals(setOf(UserField.AVATAR_URL), UserField.changedBetween(withAvatar, ada))
    }

    /**
     * The set iterates in declaration order, which is what keeps the string
     * `UserFieldSetConverter` stores stable: two equal sets built in different orders must
     * produce the same column value, or a row rewrites itself on every save.
     */
    @Test
    fun `changedBetween iterates in declaration order`() {
        val other = User(id = "1", displayName = "Grace", email = "grace@example.com", avatarUrl = "x")

        assertEquals(
            listOf(UserField.DISPLAY_NAME, UserField.EMAIL, UserField.AVATAR_URL),
            UserField.changedBetween(ada, other).toList(),
        )
    }
}
