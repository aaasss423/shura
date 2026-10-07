# Translation

## Current scope

Python `TranslationEngine` and the Android reader translation model are boundaries only; no paid API or engine is included.

Android implements the reader-side contract:

- `reader/translation/TranslationSettings.kt` defines the mode model (`OFF`, `FULL`, `FOLLOW_READING`), a pure `ReaderTranslationPlanner` that decides which pages to translate ahead of the visible page (FULL translates the whole chapter; FOLLOW_READING stays within a bounded look-ahead of the user's position), and a `TranslationSettingsStore` that keeps the mode enabled across chapter switches until the user turns it off.
- The reader bar exposes a `Translate` control; it is inert (`BuildConfig.TRANSLATION_ENABLED=false`) until an engine exists.
- The control and any future rendered translation live inside the reader UI composable. No system screen overlay, `SYSTEM_ALERT_WINDOW`, or accessibility-service interception is used; reader touch handling (scroll/pinch/tap/swipe) is never preempted. `ReaderTouchSafetyTest` asserts the overlay permission is absent.

## Boundaries

- Full translated offline chapter cache and language selection are not implemented.
- No engine, API key, or paid dependency is configured.
- Any real engine must accept `TranslationRequest` pages and return translated assets through the existing boundary; it must not read the screen or consume touch input.