# Token storage

Where the access and refresh tokens live, what protects them, and what happens when that
protection stops working.

## What is on disk

`filesDir/datastore/auth_tokens.preferences_pb`, holding two values:

| Preference key             | Contents                                   |
|----------------------------|--------------------------------------------|
| `access_token_ciphertext`  | `Base64( IV ‖ ciphertext ‖ tag )`          |
| `refresh_token_ciphertext` | `Base64( IV ‖ ciphertext ‖ tag )`          |

AES-256-GCM, 12-byte IV, 128-bit tag. The key is an `AndroidKeyStore` entry under the alias
`com.kojo.boilerplate.auth_tokens`, generated on first use and never exported. On a device with
a TEE or a secure element the key material never enters this app's address space at all: the
`SecretKey` object is a handle, and the encryption happens on the other side of the keystore
daemon.

Three classes, and the split between them is the design:

- `AesGcmTokenCipher` — the format. Plain JCA, so it runs on any JVM and is tested like
  ordinary code.
- `AndroidKeystoreSecretKeySource` — where the key comes from. Device-only, and deliberately
  small for that reason.
- `DataStoreTokenProvider` — the store. Holds a `TokenCipher` and knows nothing about keys.

## Why not `EncryptedSharedPreferences`

`SPEC.md` names it as one of two options, and this is the other one. Three reasons:

1. **`androidx.security:security-crypto` is deprecated.** Jetpack marked the library deprecated
   rather than promoting `1.1.0` past alpha, and the guidance is to use the keystore directly.
   Adding a deprecated dependency to close a security item is the wrong direction. Worth
   knowing that this one reason could not be checked from the environment the change was
   written in — Google Maven is unreachable there, so the artifact's own metadata was not read
   — which is why it is the first of three and not the only one. The other two stand on their
   own.
2. **It would be a second store.** The tokens already live in a `Preferences` `DataStore`
   alongside the theme and the user preferences. `EncryptedSharedPreferences` is
   `SharedPreferences`, so adopting it means two storage mechanisms with two threading models,
   and `DataStoreTokenProvider`'s flow — which the session-expiry listener collects — would have
   to be rebuilt on a change listener.
3. **The same key, with less of it visible.** `EncryptedSharedPreferences` is Tink over a
   keystore-held master key; what is written here is the same construction with the parts named
   in the repository instead of behind a library. For a boilerplate, that is the point.

What it costs: Tink's key rotation and its separately-encrypted keys, neither of which this app
uses. If you need either, the seam to replace is `TokenCipher`.

## The failure that has no symptom

**A restored backup.** `android:allowBackup` is `true`, and Auto Backup copies
`filesDir` off the device. The keystore key does not go with it — that is most of what a
keystore is for — so restored ciphertext is well-formed and permanently unreadable. Nothing
throws. The reader simply cannot sign in on their new phone, and there is nothing in the log.

Both `app/src/main/res/xml/backup_rules.xml` and
`app/src/main/res/xml/data_extraction_rules.xml` exclude the store, because the platform reads
`android:fullBackupContent` below API 31 and `android:dataExtractionRules` from 31 up, never
both. `TokenStorageContractTest` fails the build if either file stops naming it, if the manifest
stops naming either file, or if the store's name drifts away from the paths they exclude.

`device-transfer` is excluded as well as `cloud-backup`. A direct handset-to-handset transfer
feels like the safer channel because no cloud is involved, and the keystore key does not travel
over it either.

## Reading a store that cannot be decrypted

`TokenCipher.decrypt` returns `null` rather than throwing when a value cannot be recovered: a
bad tag, a key invalidated by a lock-screen change, a key cleared with the app's data, a value
that is not Base64. `StoredTokens.read` turns that into `StoredTokenState.Discard`, and
`DataStoreTokenProvider` clears the store and reports nobody signed in.

A failure that is *not* about the stored value — a keystore that cannot be reached at all,
which surfaces as a `ProviderException` — propagates instead, and fails the request that asked
for the token. Mapping that to `null` too would spend the reader's session on a transient
hardware fault and say nothing about it.

## Migrating off the plaintext store

Earlier versions wrote the tokens to `access_token` and `refresh_token` as plaintext. An
installation carrying them is migrated on the first read: the pair is adopted, written back
encrypted, and the plaintext keys are deleted. Every write path deletes them, not just the
migration, so a sign-out or a refresh on an installation that never read its tokens first does
not leave them behind.

The session is deliberately kept rather than dropped. Signing everyone out on upgrade looks like
the cautious choice and buys nothing: anything that could read the file already has the token,
and clearing the app's copy does not revoke it server-side.

`TokenStorageContractTest` asserts the two legacy keys are only ever *removed*. They are the one
place in the app where a token could be written in the clear, and doing so would break no test —
the app reads the encrypted pair.

## What this does not protect against

- **A compromised process.** A bearer token is plaintext at the moment it goes into an
  `Authorization` header, and `DataStoreTokenProvider` caches the decrypted pair in memory for
  the life of the process. Encryption at rest keeps the token from outliving the process *on
  disk*; it is not a defence against something already inside it.
- **A rooted device, or a debuggable build.** Root can ask the keystore to decrypt, because the
  key is bound to the app's uid and not to anything root cannot assume. That is what the Play
  Integrity item later in `SPEC.md` is for.
- **A stolen token in flight.** Certificate pinning, the next item in Phase 11.
- **An unlocked device with an active session.** No `setUserAuthenticationRequired(true)` on
  this key: the background sync signs its requests with this token and would fail silently on a
  locked device. Biometric gating belongs on a step-up secret, with a foreground prompt to
  attach it to. Adding it is one line on the builder in `AndroidKeystoreSecretKeySource`.

## Rotating the key

There is no rotation schedule, and for a session credential there does not need to be one: the
tokens themselves rotate on every refresh, so the key protects a value that is already
short-lived. To rotate anyway — after a suspected compromise — delete the alias from the
keystore. Every stored token becomes undecryptable, which the store already handles as
`Discard`: the reader is signed out and signs in again against a freshly generated key.
