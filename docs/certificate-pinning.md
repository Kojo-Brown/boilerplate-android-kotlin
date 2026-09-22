# Certificate pinning

What this app pins, what it deliberately does not, and the procedure for rotating a key without
bricking every installed copy.

## The short version

Pinning is **off** in this repository and the machinery for it is complete. `gradle/certificate-pins.properties`
is empty, so `BuildConfig.CERTIFICATE_PINS` is empty, so `PinningPolicy.parse` returns
`PinningPolicy.UNPINNED` and both OkHttp clients are handed `CertificatePinner.DEFAULT`, which
pins nothing. Fill that file in and every layer below it starts enforcing.

It is empty because the pins would have to name the public keys of a real server and this
boilerplate points at `api.example.com`. Wrong pins are strictly worse than no pins: every
request fails, and no change on the server can fix it, because the app is what holds the pin.
`:data:compileReleaseKotlin` fails while the file is empty, so shipping unpinned is a decision
somebody has to make rather than one they can drift into.

## What is pinned

The **subject public key info** — the key — and not the certificate.

```
sha256/ + Base64( SHA-256( DER of the certificate's SubjectPublicKeyInfo ) )
```

This is the distinction the whole rotation story rests on. A certificate expires and is replaced
routinely, often yearly, often automatically. If the replacement is a re-issue of the *same key*
— which is what happens unless you ask for otherwise — then its SPKI hash is unchanged and the
pin still matches. Pinning the certificate instead would make every ordinary renewal an app
release.

Compute one from a live host:

```sh
openssl s_client -servername api.example.com -connect api.example.com:443 </dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

Or from a key you hold but have not deployed — which is how you produce a backup pin:

```sh
openssl pkey -in backup.key -pubout -outform der \
  | openssl dgst -sha256 -binary \
  | openssl enc -base64
```

OkHttp will also tell you. A pinning failure's message lists the pins of the chain it was shown
beside the ones it was configured with, which is usually faster than either command.

## Where the pins live

`gradle/certificate-pins.properties`, read at configuration time by `data/build.gradle.kts`,
validated there, and written into two `BuildConfig` fields:

| Field | Contents |
|---|---|
| `CERTIFICATE_PINS` | `host=pin\|pin;host=pin\|pin`, hosts sorted |
| `CERTIFICATE_PIN_EXPIRY` | An ISO-8601 instant, or empty |

They are not secrets. An SPKI hash is derived from a certificate that every TLS client on the
internet is shown on request; publishing one gives away nothing. They are *deployment
configuration*, which is why they are a file the build reads rather than a constant in Kotlin:
they change on a key rotation, which is not a code change.

The build validates before it interpolates, because `buildConfigField` takes the source text of
the value and everything in it ends up inside a Kotlin string literal in generated code. Hosts
and pins are constrained by regex to character sets containing no quote, backslash or `$`; the
expiry is re-serialised from a parsed `Instant` rather than passed through. A `sha1/` pin is
rejected outright — SHA-1 has a public collision and there is no reason to write a new one.

## The rules the build enforces

**At least two pins per host.** One pin is one key's lifetime. The second is a key that is
generated, kept offline and not serving traffic — so that the day the first has to go, the switch
is a server deployment rather than an app release plus however long it takes every user to
install it. `CertificatePinningHandshakeTest` has this as an executable test: the app pins two
keys, the server serves the second, the connection succeeds.

**An expiry is mandatory, and past it pinning stops.** See below.

**The host this build talks to must be covered.** OkHttp treats a host with no matching pins as
unpinned rather than as an error, so a typo in a hostname, or a `*.` where `**.` was meant, is
pinning that silently protects nothing while the client still carries a pinner and every test
stays green. `PinningPolicy.decideFor` compares the pin set against `BuildConfig.BASE_URL`'s host
and throws when they do not meet, on the first injection of an `OkHttpClient` — app start. A
build in that state fails on every developer machine and in CI before it can ship.

Note that `*.example.com` matches exactly one label below `example.com` and does **not** match
`example.com` itself. `**.example.com` is the one that matches any depth.

## Fail closed on misconfiguration, fail open on expiry

These are opposite treatments of two failures that look similar, and the difference is who can
fix them.

A pin set that does not cover the host is a mistake in the *build*, and the person running the
build can fix it in seconds. So it throws.

An expired pin set is a build that was correct when it shipped and has been overtaken. The only
devices that reach the expiry are running a version old enough that its pins may name keys nobody
holds any more, and refusing to connect there is a brick with no recovery path — not even an
in-app update prompt, because that needs the network too. So enforcement stops and the connection
falls back to ordinary system trust, which is what every app without pinning already has. This is
exactly what Android's own `<pin-set expiration="…">` does, for the same reason.

Pick the expiry as roughly the point past which you would no longer trust a build of this app to
know the current keys. Months, not years. It is not a security boundary — it is the fuse on one.

The decision is taken once, when the singleton `OkHttpClient` is built, so a process that outlives
the expiry keeps enforcing until it is restarted. A process lives hours and the expiry is months
out; re-deciding per request would mean a policy changing underneath a connection pool.

## The rotation procedure

Assume the app pins `KEY_A` (serving) and `KEY_B` (backup, offline).

1. **Deploy a certificate for `KEY_B`.** The installed app already pins it, so this is a server
   change with no client release. Traffic keeps flowing throughout — the pin set is a union, and
   both keys are in it.
2. **Confirm.** Run the `openssl s_client` command above against production and check the hash it
   prints is `KEY_B`'s. Do this before step 3, not after: step 3 is the one that cannot be undone
   from the server side.
3. **Generate `KEY_C`** and keep it offline, wherever `KEY_B` was kept. It is the new backup.
4. **Update `gradle/certificate-pins.properties`** to `KEY_B, KEY_C`, push the expiry out, and
   release. `KEY_A` leaves the pin set here, which is the step that retires it.
5. **Wait for adoption** before retiring `KEY_B` in turn. Installations still on the previous
   release pin `KEY_A, KEY_B`, and the server is on `KEY_B` — they are fine, and they stay fine
   for as long as `KEY_B` is serving. The next rotation cannot begin until enough of them have
   moved on; that window, not the certificate's expiry, is what sets the pace.

**If a key is compromised** the order changes and there is no comfortable version of it. Deploy
the backup key immediately (step 1), which every current installation already accepts, then go
straight to step 3. Installations too old to pin the backup will fail until they update. That
window is exactly what the pin-set expiry bounds, and it is the strongest argument for keeping
the expiry short and the release cadence regular.

## What a pinning failure looks like

`javax.net.ssl.SSLPeerUnverifiedException`, with a message naming the host, the pins the peer
presented and the pins configured.

It is **not retried**. `isTransientFailure` in `:core:common` excludes it explicitly: it means
the chain the server presented is not one this build accepts, which is a property of the app and
the server rather than of the moment, so the next attempt gets the same chain and the same
verdict. Left in the transient set it would be retried at every layer that retries —
`retryWithBackoff`, then `RetryingUserRepository` under it — burning each screen's backoff budget,
and it would surface to the user as "network trouble, try again", which is the one wrong thing to
say about the one failure that means somebody may be in the middle of the connection.

The carve-out is `SSLPeerUnverifiedException` alone and not its parent `SSLException`. A handshake
killed mid-flight by a dropped connection arrives as an `SSLException` and is exactly as transient
as any other dropped connection.

## Debugging through a proxy

Leave the pin set empty for the build you are proxying, which is what debug builds do by default
here. Do **not** reach for `sslSocketFactory` or `hostnameVerifier` — `CertificatePinningContractTest`
fails the build on both, because they replace the checks that decide whether the peer is the
server at all, they are added temporarily, and nothing about the app's behaviour changes when the
reason for them is forgotten.

You will still need the proxy's CA in the device's trust store, which on API 24 and above means a
`network_security_config` debug override. That is a separate mechanism from this one and it is
correctly limited to debug builds by the platform.

## What is not done here

- **No pinning of an intermediate or root CA.** Pinning the issuer instead of the leaf makes
  rotation nearly free — any certificate that CA issues you matches — at the cost of trusting
  everything else that CA issues to anybody. Worth it for some deployments; it is a decision
  about a specific CA, so it is not one a boilerplate can make.
- **No remote pin updates.** A pin set delivered over the network is a pin set an attacker who
  can already intercept the network may be able to influence, and bootstrapping it needs a
  pinned connection anyway. The expiry above is the simpler answer to the same problem.
- **Nothing runs on a device.** The handshake test runs on a JVM against MockWebServer, which
  exercises OkHttp's pinner for real but not the device's TLS stack.
- **Nothing pins the CI environment.** CI assembles a debug build, and debug builds here are
  unpinned by design.
