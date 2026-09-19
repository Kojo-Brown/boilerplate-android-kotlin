# Dynamic colour, edge-to-edge, and semantics

Three subjects that arrive together because they are the same subject: what the app looks like to
a person who is not the person it was designed for. A wallpaper-derived palette is the system
saying what "this device's colours" means; edge-to-edge is the system saying where its own
furniture is; semantics is the app saying what it contains to something that cannot see it. All
three are cases of not assuming.

## Dynamic colour

`BoilerplateTheme` takes the palette from the wallpaper on Android 12 and above
(`dynamicLightColorScheme` / `dynamicDarkColorScheme`) and falls back to the hand-written schemes
in `Color.kt` below that. `dynamicColor` is a parameter rather than a constant so a caller that
needs a fixed palette — a screenshot test, most of all — can have one.

The consequence worth stating: **nothing in this app may hardcode a colour.** A dynamic scheme
means `MaterialTheme.colorScheme.primary` is whatever the user's wallpaper made it, and a literal
`Color(0xFF6650A4)` next to it is a colour that stopped matching the moment the wallpaper changed.
Contrast is the same argument — the on-colours are computed to pair with their containers, and a
hand-picked foreground over a dynamic background is a contrast ratio nobody has checked.

## Edge-to-edge

The window is edge-to-edge, which means the app draws behind the status bar and the navigation
bar and is responsible for keeping its content out from under them.

### The system bar icons follow the app's theme, not the device's

`enableEdgeToEdge()` with no arguments gives both bars a `SystemBarStyle.auto` whose
`detectDarkMode` reads `Configuration.UI_MODE_NIGHT_MASK`. That is the *device's* night mode, and
this app also has a `ThemeMode` preference, so the two disagree for every reader who has chosen
Light on a device in dark mode or Dark on one in light mode. The visible result is white
status-bar icons over a white app bar: the clock, the battery and the signal strength gone.

`MainActivity` therefore passes both styles, with `detectDarkMode` built from
`ThemeMode.isDark(isSystemInDarkTheme())` — the same function `BoilerplateTheme` picks its colour
scheme with, which is why the glyphs and the surface beneath them cannot drift apart. It lives in
`:core:common` because it is the one thing `:app` and `:core:ui` both have to agree about.

It is done inside a `DisposableEffect` keyed on the resolved answer, because the preference can
change while the activity is resumed. The bare `enableEdgeToEdge()` before `setContent` stays:
without it the first frame is drawn inside the system bars, since the effect only runs after the
first composition.

### Insets

`Scaffold` computes the padding its content must avoid and applies none of it — it hands the
content lambda a `PaddingValues` and expects the caller to use it. Dropping that parameter
compiles, renders, and looks correct on whichever device the developer has open; what it produces
elsewhere is content underneath the status bar or the navigation bar.

`AccessibilityContractTest` fails a `Scaffold` whose content lambda never reads its parameter. It
also fails a file that calls `enableEdgeToEdge` without naming both bar styles somewhere.

## Semantics

### Say what a control *is*, not what tapping it will do

A control with two states is a toggle, and a toggle has a role and a state the platform knows how
to announce. `FlashToggle` is the worked example: it was an `IconButton` whose
`contentDescription` flipped between "Enable flash" and "Disable flash", which tells a screen
reader what the next tap does and never what the flash is doing now. As an `IconToggleButton` it
sits on `Modifier.toggleable`, so the role and the checked state are in the semantics tree and
the accessibility service decides the wording — with `stateDescription` supplying "On"/"Off" in
place of the checkbox vocabulary the role would otherwise use.

The icon shows the state rather than the action for the same reason. A control that announces
"Flash, On" while drawing a struck-through flash contradicts itself.

### Say when something has changed on its own

Anything that appears without the user having acted needs `liveRegion`, or a screen reader will
never reach it: nothing moves focus there, and the user has no reason to swipe back and look.
`OfflineBanner` and the refresh spinner in `HomeScreen` are both `LiveRegionMode.Polite` —
`Assertive` interrupts whatever is being read, which is for something that invalidates it.

An indeterminate `CircularProgressIndicator` has no semantics of its own. `progressSemantics`
applies to the determinate overload only, so an unlabelled spinner is a screen that has gone
silent for as long as it is loading. `LoadingIndicator` carries a `contentDescription` for that
reason, and takes it as a parameter because "Loading" is rarely the most useful sentence
available.

### Headings

`TopAppBar` draws its title with a type style and no semantics, so a screen title is one more
`Text` among the others. Every screen marks its title `heading()`, which is what TalkBack's
swipe-by-heading navigates between — the fastest way past a screen a reader has already heard.

### Decorations say nothing

An icon that repeats what the text beside it says takes `contentDescription = null`. For a
composable that is not an `Icon` or an `Image` there is no description to null out, so the way to
say "decorative" is `Modifier.clearAndSetSemantics {}` with an empty block, which drops the
subtree from the semantics tree. `UserMonogram` is the case: it draws the first letter of a name
that the label beside it announces in full, so left alone the reader hears "K" and then "Kelsey
Turner".

## What is not covered here

- **Text scaling.** `UserMonogram` sizes its glyph in `sp` off a `dp` container, so it does not
  grow with the system font scale. That is deliberate for a monogram — the alternative is a letter
  that overflows a fixed circle — but making the circle itself scalable is open work, and its
  KDoc says so.
- **Touch target sizes.** Every interactive control in the app is a Material 3 component, and
  those carry `minimumInteractiveComponentSize` themselves. Nothing enforces it for a control
  that is not.
- **Contrast ratios.** Under dynamic colour these are the system's to guarantee, and it does so
  only for correctly paired scheme roles — which is the rule about hardcoded colours above, and
  is not currently checked by anything.
- **A `AccessibilityChecks`-enabled instrumentation pass.** The androidTest suite does not run in
  CI, which needs an emulator; see `SPEC.md`.
