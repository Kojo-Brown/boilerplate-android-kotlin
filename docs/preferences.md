# Typed preferences

The app has two preference stores and they are not the same kind of thing. This page is about
the typed one — Proto DataStore — what it buys over the untyped one sitting next to it, and the
three boundaries that keep the difference from leaking.

| | Preferences DataStore | Proto DataStore |
|---|---|---|
| Where | `auth_tokens`, `theme_preferences` | `user_preferences.pb` |
| Schema | none; a key is a string paired with a type at the call site | `core/datastore-proto/src/main/proto/user_preferences.proto` |
| A misspelled key | reads back the default, silently | does not compile |
| A wrong type | `ClassCastException` at read time | does not compile |
| Nested values | not expressible | a nested message |
| Cost | a dependency | a dependency, a Gradle plugin, and protoc |

Neither is a replacement for the other. The tokens and the theme are two and one flat values,
and rewriting them onto a schema would buy nothing anybody could point at. The rule of thumb
this repository follows: **a store with more than a handful of keys, or with two values that
have to change together, is a schema.**

## The schema

```proto
message UserPreferencesProto {
  SyncPreferencesProto sync = 1;
  bool onboarding_complete = 2;
}
```

`sync` is a nested message rather than three sibling fields, and that is the part worth
understanding, because it is the thing the untyped store cannot do at all.

Three preference keys can be written one at a time. A user who switches the sync policy and
turns off "only while charging" in the same settings screen produces two writes, and a crash
between them leaves a combination on disk that was never on screen. One nested message is one
write: `setSyncPreferences` replaces the block, and DataStore's rename-over-a-scratch-file means
the reader sees the whole of the old value or the whole of the new one.

The second half of that is `recordSuccessfulSync`, which is the *opposite* case — a narrow write
that must not disturb the rest of the block. It does its read inside `updateData` rather than
copying a value observed earlier:

```kotlin
dataStore.updateData { current ->
    current.toBuilder()
        .setSync(current.sync.toBuilder().setLastSuccessfulSyncEpochMillis(at.toEpochMilli()).build())
        .build()
}
```

The version that reads better — `setSyncPreferences(observed.copy(lastSuccessfulSync = at))` —
reverts any policy change that landed since `observed` was read, silently, and the background
sync is both the most frequent writer here and the one holding the stalest copy of everything
else. `updateData` serialises its transforms and hands each one the current contents, which is
the whole reason it takes a lambda instead of a value.

## Three boundaries

**The module.** `:core:datastore-proto` holds the `.proto` file and nothing else — no Kotlin, no
annotation processor. That is a build decision before it is an architectural one: protoc emits
Java source during the build, `:data` runs KSP for Room and Hilt, and a generated-source
directory that KSP reads without depending on the task that writes it is a Gradle
implicit-dependency failure on a good day and a race on a bad one. It is an `implementation`
dependency of `:data`, so nothing above `:data` can name a generated type.

**The model.** `UserPreferencesDataSource` maps `UserPreferencesProto` to the Kotlin
[`UserPreferences`](../core/common/src/main/kotlin/com/kojo/boilerplate/core/common/preferences/UserPreferences.kt)
in `:core:common`, and that mapping is where three schema facts stop:

- *`0` means never.* `last_successful_sync_epoch_millis` is `0` on a device that has never
  synced — and `0` is also a real instant, 1 January 1970. It arrives in Kotlin as
  `Instant?`, `null`, so nothing downstream can format it, compare it or subtract from it as
  though a sync happened during the Nixon administration.
- *`UNSPECIFIED` is not a value.* proto3 has no defaults: every scalar decodes to its zero, so
  "the user has not chosen" and "the user chose the thing that happens to be `false`" are the
  same bytes. `SyncPolicy.Default` is the one place the app answers that, and the schema's
  zero constant is named `UNSPECIFIED` rather than given a policy's name so that it cannot
  quietly become one.
- *`UNRECOGNIZED` happens.* A newer build of the app on the same device can write a policy
  constant this one does not know — a downgrade, a beta channel. Protobuf hands it over as
  `UNRECOGNIZED` and the mapping resolves it to the default. It is one unreadable field, not an
  unreadable file, and treating it as corruption would throw the rest away.

**The serializer.** `UserPreferencesSerializer` catches `InvalidProtocolBufferException` and
rethrows it as `CorruptionException`, because that is the only exception DataStore offers its
corruption handler a chance to answer. Anything else — an `IOException` from the stream — is
left alone deliberately: that is not corruption, and answering it by discarding the user's
settings would turn "the device is out of space" into "the app forgot your preferences".

## Corruption

`userPreferencesCorruptionHandler` replaces an unparseable file with the defaults. Without it,
`CorruptionException` propagates out of every read and the app stays broken until the user
clears its data, which costs them everything else too.

That trade is right for this file and would be wrong one file over. What is lost here is a set
of settings the user can choose again in a few taps. The same handler over a file holding
unsynced user data would silently delete work. **Corruption handling is a decision per store,
not a default to copy**, and it is declared as a named property rather than inline in the
delegate so that the tests exercise the handler the app actually installs.

## Changing the schema

Field numbers are the wire format. proto3 skips fields it does not know on read and writes
nothing for a field at its zero value, so an old build reading a new file and a new build
reading an old one both work — but only while the numbers keep meaning what they meant.

- Never renumber a field, and never reuse the number of a deleted one. `reserved` it, which
  makes reuse a compile error instead of a device reading one field as another.
- Renaming a field is safe on the wire and breaks the Kotlin that reads it, which is the right
  way round.
- An enum's zero constant is always the "unset" one, because that is what an absent field
  decodes to.

There is no version field and no migration framework. proto3's own rules cover the changes a
preferences file actually goes through, and a version int would only ever be read by code that
already has to handle both shapes. That is the opposite of the answer in
[`room-migrations.md`](./room-migrations.md), and the difference is the point: a relational
schema has a shape the engine enforces at open time, so a migration is a script that has to
exist. A protobuf message has no shape on disk at all.

## Versions

`protobuf` in the version catalog pins protoc and `protobuf-javalite` together, and they must
not drift: protobuf 4.x generated code calls `RuntimeVersion.validateProtobufGencodeVersion` in
its static initialiser and throws when the runtime is older than the compiler that wrote the
code. One catalog key for both is what makes that impossible rather than unlikely.

`lite` is not an optimisation to revisit later. The full runtime carries descriptors, reflection
and the text format — roughly a megabyte of dex, plus a class-loading path Android's verifier
walks — to support code generation and introspection a preferences file never asks for.

## What is not here

- **No consumer yet.** Nothing reads `UserPreferencesDataSource` outside its tests. The sync
  policy is the obvious first one — `WorkManagerBackgroundSyncScheduler` builds its
  `Constraints` from constants today — and wiring it means re-enqueuing the periodic work when
  the preference changes, which is its own item.
- **No migration from the theme store.** `theme_preferences` is still a Preferences DataStore.
  Moving it is a `SharedPreferencesMigration`-shaped job with a `DataMigration` and a deletion,
  and it changes what `MainActivity` injects.
- **No multi-process store.** `DataStoreFactory` is the single-process one. A widget or a
  `:remote` process reading these preferences would need `MultiProcessDataStoreFactory`, which
  is a different file lock and a different set of failure modes.
