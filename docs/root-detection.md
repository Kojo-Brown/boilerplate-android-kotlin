# Root and tamper detection

What this app can honestly claim about the device it is running on, who is allowed to believe
it, and the machinery for saying it.

## The short version

Attestation is **off** in this repository and the machinery for it is complete.
`gradle/play-integrity.properties` is empty, so `BuildConfig.INTEGRITY_CLOUD_PROJECT_NUMBER` is
empty, so `IntegrityConfiguration.parse` returns `IntegrityConfiguration.DISABLED`, so every
`attest` answers `AttestationFailure.NOT_CONFIGURED` and every request goes out without a token.
Link a Cloud project, put its number in that file, and the client half starts working.

It is empty because the number has to name a real Google Cloud project linked to a real app in
Play Console, and this boilerplate is linked to neither. A made-up number does not degrade
gracefully: Play answers `CLOUD_PROJECT_NUMBER_IS_INVALID` on every device, which is the same
outcome by a slower route and a worse error message.
`:data:compileReleaseKotlin` fails while the file is empty, so shipping a release with
attestation off is a decision somebody has to make rather than one they can drift into.

**The client half is not the feature.** A token proves nothing until a server decodes it and
acts on what is inside. Everything under [The server's half](#the-servers-half) is work that
does not live in this repository, and skipping it leaves an app that spends a round trip per
sign-in to send a header nobody reads.

## Why there is no `isRooted()`

This is the part worth reading even if you never turn attestation on.

The obvious way to write this item is a function that looks for `su` on the path, for
`test-keys` in `Build.TAGS`, for a handful of package names belonging to root managers, for
`ro.debuggable`, for an emulator's fingerprint. Every one of those checks is real, and every one
of them runs **on the device the attacker owns**. The attacker has `adb root`, Frida, a patched
APK and as many attempts as they like. Against them:

- an `if` is an `if` that gets patched out,
- a `su` binary that answers honestly is a `su` binary that gets renamed,
- a file that exists is a file that gets hidden from one process,
- and a function that returns `false` is a function that gets hooked to return `false`.

What this buys is a number of hours of an attacker's time, once, ever — and it costs a
permanent stream of false positives from the honest population, who have a custom ROM, a
work profile, an odd OEM build, or a file at a path that looked suspicious in 2016.

Root detection an app can perform is root detection an app can be made not to perform. So this
app performs none, and `IntegrityAttestationContractTest` fails the build if somebody adds some.

What an attacker cannot patch is a verdict computed on Google's servers, about a device they do
not control, signed and encrypted to a Cloud project they do not hold a key for, and evaluated
on a backend they do not run. That is the whole of what Play Integrity offers, and it is the
whole of what this app does.

## The shape

```
app                                  Google                          your backend
───                                  ──────                          ────────────
AuthApi.login
  @Headers(X-Require-Integrity: v1)
        │
        ▼
IntegrityInterceptor
  hash the request  ─────────────►  prepareIntegrityToken (once)
                                    request(requestHash)
                                          │
                                    encrypted token
                                          │
  X-Integrity-Token: <token>  ─────────────────────────────────────►  decodeIntegrityToken
                                                                          │
                                                                      verdict + requestHash
                                                                          │
                                                                      decide, here and
                                                                      nowhere else
```

| Piece | Where |
|---|---|
| The port, and the result types | `core/security/integrity/IntegrityAttestation.kt` |
| The request binding | `core/security/integrity/IntegrityRequestHash.kt` |
| The interceptor, and the marker header | `core/security/integrity/IntegrityInterceptor.kt` |
| Play's error codes, mapped | `core/security/integrity/PlayIntegrityErrorCode.kt` |
| The one file that names a Play type | `core/security/integrity/PlayIntegrityAttestation.kt` |
| The Cloud project number | `gradle/play-integrity.properties` → `data/build.gradle.kts` |

## Standard requests, not classic

The Play Integrity API has two shapes and the choice between them is a latency decision.

**Classic** (`requestIntegrityToken`) takes a caller-supplied nonce and does all the work per
call: hundreds of milliseconds to a second, and rate limits low enough that Google's own
guidance is a handful per day. It is for the rare, deliberate moment — a payout, a one-time
enrolment.

**Standard** (`prepareIntegrityToken` then `request`) splits it: a warm-up that produces a token
provider, and then per-request tokens served from that warm state in tens of milliseconds. That
is what makes attesting on the request path defensible at all, and it is what this app uses.

`PlayIntegrityAttestation` warms up once per process, guarded by a `Mutex` so that two
concurrent requests do not warm up twice, and re-warms exactly once when Play reports
`INTEGRITY_TOKEN_PROVIDER_INVALID` — the provider expired, or the user cleared Play Store data.
Once, not in a loop: a second failure of the same kind is a device that is not going to serve a
token, and retrying on the request path is latency the user pays for nothing.

## The request hash, and the replay it stops

Without a binding, a token is a bearer statement that *some* request from this app on this
device was attested. Anyone holding one — from their own rooted device, or replayed from
traffic — can attach it to any other request, and the backend decodes it, sees
`MEETS_DEVICE_INTEGRITY`, and has learnt nothing about the request in front of it.

Play copies up to 500 bytes of caller-supplied text into the verdict verbatim. Put a digest of
the request there and the backend can recompute it from what it received.

```
requestHash = base64url_nopad(
    SHA-256( "<METHOD>\n<encoded path>[?<encoded query>]\n<lowercase hex SHA-256 of the body>" )
)
```

43 characters, whatever the request was. Three decisions in it are contract rather than taste:

- **The body is hashed separately and included as hex**, so a server can digest a large upload
  as it streams rather than buffering it to build one string, and so the separator can never be
  confused with body content.
- **Scheme, host and port are left out.** They are not in dispute — the request reached this
  server — and including them means a hash that changes when a deployment moves behind a
  different hostname while the request is identical.
- **Path and query are taken already-encoded**, exactly as they go on the wire, so neither side
  has to agree on a normalisation of percent-escapes.

Nothing about the request's contents reaches Google: the verdict carries the digest, not the
path or the body.

`IntegrityRequestHashTest` carries two golden vectors. A server-side implementation that
reproduces those two lines reproduces the contract.

**It is not a nonce.** Two identical requests hash identically, so the hash alone does not stop
a replay of the *same* request. Bounding that is the server's job — see below.

## Which endpoints are attested

One: `AuthApi.login`.

Tokens come out of a per-app, per-device budget and each one costs a round trip on the request
path, so attestation is spent where it buys the most. Sign-in is that place: it is the step a
credential-stuffing script repeats, it is low-frequency for a real person, and a verdict
attached to it reaches the backend *before* a session exists rather than after.

`refreshToken` is deliberately not attested. It runs on a schedule the user does not control,
often in the background, and attesting it would spend the budget on the request least able to
wait — while proving nothing the attested sign-in that issued the refresh token has not already
proved.

To attest another endpoint, add the marker to it:

```kotlin
@Headers(IntegrityInterceptor.REQUIRE_ATTESTATION_HEADER)
@POST("payments")
suspend fun pay(@Body request: PaymentRequest): PaymentResponse
```

Nothing else changes: every `OkHttpClient` in the app already carries the interceptor, and
`IntegrityAttestationContractTest` fails the build if one stops doing so. That rule exists
because a marked endpoint on an unattested client sends `X-Require-Integrity` to the server
verbatim and no token — a silent failure that looks exactly like a device that could not attest.

## Failing open, on purpose

When no token is available the request goes out without one.

Refusing to send is a client-side security decision, which is the category Play Integrity exists
because the client cannot be trusted to make: an attacker running a patched build removes the
refusal in an afternoon. What it *would* reliably do is lock out the honest population it cannot
help — a device with Play services disabled by an MDM profile, someone who force stopped the
Play Store, anyone briefly offline — and lock them out in the one place with no recovery path,
because the request that would fetch the fix is the request being refused.

The server sees the absence and weighs it. It knows whether this account has ever presented a
valid token, what the endpoint is worth, and what a plausible rate of unattested traffic looks
like. None of that is knowable in the app.

`AttestationFailure` reduces Play's seventeen error codes to what the app does differently:

| Outcome | Transient | What produced it |
|---|---|---|
| `NOT_CONFIGURED` | no | no cloud project number in this build |
| `PLAY_UNAVAILABLE` | no | no Play Store or Play services, either too old, an install Play does not recognise, a UID mismatch |
| `TRANSIENT` | yes | network, Google's backend, a device-side hiccup, an unrecognised code |
| `RATE_LIMITED` | yes | `TOO_MANY_REQUESTS` |
| `PROVIDER_EXPIRED` | yes | the warmed-up provider went stale; re-warmed once, in `attest` |
| `MISCONFIGURED` | no | a cloud project number that is not this app's, a request hash over the limit |

`PLAY_UNAVAILABLE` is the verdict-shaped one and the reason none of this is acted on locally. A
sideloaded build, a de-Googled ROM and an emulator without Play services all land there — which
overlaps heavily with the population this item is about. It is still not evidence: a corporate
device with Play services disabled lands there too. The app does not decide; the server sees a
request with no token, which is the same fact stated in the one place that can weigh it.

## The server's half

None of this lives in this repository. It is the half that makes the other half mean something.

1. **Decode.** `POST https://playintegrity.googleapis.com/v1/<package>:decodeIntegrityToken`
   with `{"integrity_token": "<token>"}`, authenticated with a service account in the linked
   Cloud project. The service-account key is a real credential: it belongs in the server's
   secret store and never comes near this repository or the APK.
2. **Check `requestDetails.requestPackageName`** is this app's package. A token minted for a
   different app decodes perfectly well.
3. **Check `requestDetails.requestHash`** equals the hash the server computes from the request
   it received, by the formula above. This is the binding; without this step a token is a
   bearer token for the whole API.
4. **Bound the replay window.** `requestDetails.timestampMillis` — reject anything older than a
   minute or two — and remember spent tokens for at least that long. Two identical requests
   hash identically, so the hash alone does not make a token single-use.
5. **Then, and only then, weigh the verdict.** Roughly:
   - `deviceIntegrity.deviceRecognitionVerdict` containing `MEETS_DEVICE_INTEGRITY` is a genuine
     certified Android device. An **empty array** is the interesting one: it means the device
     shows signs of attack — rooted, or an emulator without Play support.
   - `appIntegrity.appRecognitionVerdict` of `PLAY_RECOGNIZED` is the binary Play distributed.
     `UNRECOGNIZED_VERSION` is a modified or repackaged build.
   - `accountDetails.appLicensingVerdict` of `LICENSED` is an install Play sold to this account.
   - `UNEVALUATED` anywhere means Play declined to answer, not that the answer was good.

Decide by what the endpoint is worth, and prefer friction to refusal: a step-up challenge, a
lower rate limit, a hold on the payout, a flag for review. A hard block on a verdict is a
support queue full of people with an unusual phone.

## Rolling it out

The order matters, because an enforcing backend in front of a population that has never sent a
token rejects all of it.

1. Link the Cloud project, fill in `gradle/play-integrity.properties`, ship the client. It sends
   tokens; nothing enforces.
2. Have the backend decode and **log** — verdicts, absences, hash mismatches — without acting.
3. Read the distribution. The unattested share is the honest population you are about to affect:
   old app versions that predate step 1, devices without Play, and your own test rigs.
4. Enforce on the highest-value endpoint first, with the softest action that works.

Reverse the order and step 4 is an incident.

## Known limits

- **A token is worth one request.** It says nothing about the session it opens. An attacker who
  passes an attested sign-in on a clean device and then moves the session token to a rooted one
  is not visible to any of this. Binding a session to a device is a different mechanism.
- **The standard API's verdict is thinner than the classic one's.** In particular, an app that
  needs the environment details (`playProtectVerdict`, `appAccessRiskVerdict`) is asking for a
  classic request, at classic latency and classic rate limits.
- **Attestation is not availability.** Play's backend has outages, and the client treats them as
  transient by design. A server that hard-requires a token has taken a dependency on Google's
  uptime for its sign-in.
- **`MEETS_BASIC_INTEGRITY` is weaker than it sounds.** It is the "passes basic checks" tier and
  it is met by devices that fail `MEETS_DEVICE_INTEGRITY`. If the point is an unmodified device,
  the field to read is the strong one.
