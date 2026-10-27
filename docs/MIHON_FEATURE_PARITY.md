# Shura / Mihon Feature Parity Audit

This checklist records behavior found in the repository, not controls or screens
that merely look interactive. It is an implementation snapshot; a status of
“Partially implemented” does not imply the remaining behavior is available.

**Status meanings**

- **Implemented** — the behavior is connected to application logic and has
  supporting code or tests.
- **Partially implemented** — a useful subset works, but one or more requested
  behaviors are absent or incomplete.
- **Missing** — no working implementation was found.
- **Blocked by an external dependency or configuration** — the application has
  an implementation path, but it cannot operate until an external service,
  credential, or deployment configuration is supplied.

## Interface and navigation

| Feature | Status | Evidence and limits |
|---|---|---|
| Home, source discovery, library, downloads, settings, and reader destinations | Implemented | Compose destinations are connected through the navigation stack in `android/app/src/main/java/app/shura/manga/ui/ShuraRoot.kt`. |
| Arabic and English UI with RTL layout | Implemented | Localized strings and runtime layout direction are applied by `ShuraRoot`; Arabic is the default in `MainActivity`. |
| Shura light and dark themes | Implemented | Brand palettes are defined in `ui/theme/ShuraTheme.kt`; the selected mode persists in `MainActivity`. |
| Android dynamic colors | Implemented | Optional setting uses wallpaper-derived Material 3 colors on Android 12+; Shura colors remain the default and older Android uses the brand palette. |
| Material 3 styling, responsive library grid, loading/error/empty states | Partially implemented | Compose Material 3 screens and state-specific messages exist. A complete accessibility and real-device layout audit has not been run. |
| Smooth transitions and motion polish | Partially implemented | Reader controls use visibility animations; there is no app-wide motion audit. |
| Accessibility and reader touch safety | Partially implemented | `ReaderTouchSafetyTest` checks reader interaction and that overlay permission is absent. Full gesture/accessibility verification requires broader instrumentation and real-device testing. |

## Library, discovery, and manga details

| Feature | Status | Evidence and limits |
|---|---|---|
| Follow and remove manga from the library | Implemented | Followed works persist under app-private storage in `WorkLibrary.kt`; work cleanup is wired from the library. |
| Search, sort, adaptive grid, and bulk selection | Partially implemented | Library search and recent/title/chapter sorting plus bulk actions exist in `ui/screens/LibraryScreen.kt`; no list-layout switch or category filters. |
| Favorites and user-defined categories | Partially implemented | Followed-work records persist a favorite flag and one user-named category per work; the library exposes favorite/category filters and bulk assignment. Category management is intentionally limited to assignment/removal through the selected-work workflow; there is no separate category reorder or rename screen. |
| Read progress, continue reading, and bounded reading history | Implemented | `ReadingProgressStore` persists per-chapter progress and a bounded history; Home resumes entries through local files or the source. |
| Browse and search installed manga sources | Implemented | Sources are registered through `SourceHost`; source search and result navigation are connected. |
| Manga details with cover/title/source/chapter count and chapter actions | Partially implemented | Details fetch chapters and support start/continue, read/unread state, sorting and downloads. The source API does not expose a complete description/genres/publication-status model. |
| Chapter filtering by unread/read and sorting | Partially implemented | Read state is derived from reading history and unread filtering/sorting are present; downloaded-only filtering and a separate chapter-search control are incomplete. |
| Download selected chapter batches | Implemented | Details batches skip already downloaded, queued, or read chapters and hand remaining chapters to the queue. |

## Sources, repositories, and extensions

| Feature | Status | Evidence and limits |
|---|---|---|
| Built-in and extension-backed source access | Implemented | MangaDex and supported loaded extensions use the source API/host boundary. |
| Add, refresh, remove, and inspect extension repositories | Implemented | Repository manager/client validates HTTPS metadata, repository identity/key, size and package metadata before installation. |
| Extension install/update/remove with compatibility and signing checks | Partially implemented | APK verification, signature policy and compatibility tests exist. Only supported ABIs/publishers can run; availability depends on compatible signed artifacts. |
| Source details/configuration and useful errors | Partially implemented | Repository and install errors are surfaced; there is no general per-source configuration UI or uniform source health page. |
| Treat every safe-classified extension as trusted | Implemented (safely rejected) | Trust is not inferred from a “safe” classification; publisher/signature checks remain independent and have dedicated tests. |

## Reader and offline content

| Feature | Status | Evidence and limits |
|---|---|---|
| Continuous vertical and page-by-page reading | Implemented | `ReaderScreen` implements a vertical list and horizontal pager with shared progress reporting. |
| Zoom, double-tap zoom, page retry, and chapter navigation | Implemented | Page image gestures, retry callbacks and previous/next chapter callbacks are wired. |
| Save and restore reading position | Implemented | Progress is persisted by source/work/chapter and clamped when reopening. |
| Configurable reading direction (LTR/RTL independently of UI language) | Partially implemented | The interface direction follows the selected UI language; there is no independent reader-direction preference. |
| Page fit/scaling modes and configurable reader gestures | Partially implemented | Zoom is available; fit modes and a configurable gesture map are not present. |
| Download queue, progress, duplicate prevention, cancel, and retry | Implemented | `DownloadQueue` serializes work, tracks progress, rejects duplicate chapters and exposes cancel/retry actions. |
| Pause/resume, Wi-Fi-only downloads, and durable interrupted queue recovery | Missing | Queue rows/jobs are in-memory and there are no pause/resume or network-policy controls. |
| Offline reading of downloaded chapters | Implemented | Stored chapters are opened from local files without requiring source requests for page contents. |
| Local image-folder and comic archive import | Partially implemented | The app can import selected document images; folder-tree and common comic archive formats (CBZ/CBR/ZIP/RAR) are not implemented. |
| New manga chapter checks, configurable update schedule, and notifications | Missing | The store's extension-update screen is not a library chapter updater; no periodic chapter refresh or notification scheduler was found. |

## Translation, tracking, and backup

| Feature | Status | Evidence and limits |
|---|---|---|
| Text translation client/provider contract | Partially implemented | `HttpTranslationEngine`, chapter request/result models, planner, and cache exist. The client calls the separate translator's text API, but the Android app does not configure or instantiate it. |
| OCR of manga page images and source-language detection per region | Missing | No Android OCR/image-text recognition implementation or page-region model was found. |
| Translate only non-target-language regions and handle speech/narration/SFX | Missing | There is no OCR-region language classifier or region-selection pipeline. |
| Background page analysis, cancellation/reprioritization, and OCR cache | Missing | The controller/planner are not connected to page images or reader page lifecycle; only translated text has a cache model. |
| Render translated text as positioned page overlays, including Arabic shaping | Missing | Cached text can appear in a separate text surface below a page, not at detected image coordinates. There is no overlay renderer or inpainting. |
| Configure translation service/provider/endpoint/target language in the app | Missing | The translator service supports external engine configuration, but Android settings have no endpoint/provider/credential configuration and the build flag is disabled. Credentials must remain outside the APK. |
| Translation service credentials/deployment | Blocked by an external dependency or configuration | The separate translator service must be deployed and configured to make its text API reachable. This does not resolve the missing image OCR and overlay implementation. |
| Adapter-based tracking and progress synchronization | Missing | No tracking adapter, authentication flow, or service synchronization implementation was found. |
| Backup/restore of library, progress, settings, and repositories | Missing | No backup export/import flow or backup format validator was found. |

## Settings and persistence

| Feature | Status | Evidence and limits |
|---|---|---|
| Language, appearance, repository, extension, and version settings | Implemented | These controls are connected to persisted state or real destinations. |
| Reader preferences | Partially implemented | Reader mode, brightness, preload, screen-awake, parallelism and delete-after-reading are persisted; requested fit, direction, gestures and translation provider settings are absent. |
| Download/storage controls | Missing | No Wi-Fi-only setting or storage quota/cleanup policy UI was found. |
| Translation settings | Partially implemented | The reader stores an off/full/follow mode and cached text, but no real image translation is available. The UI explicitly reports that page OCR/overlay translation is unavailable. |
| Backup settings and credential-safe backup policy | Missing | No backup implementation exists; no credential exclusion behavior can therefore be verified. |

## Verification notes

- The Android repository includes JVM tests for reader state/navigation,
  downloads, library behavior, repository validation/trust, and translation
  planning/client behavior.
- `android/app/src/androidTest` contains a reader touch-safety test, but source
  presence alone does not establish that it has run on a device in this audit.
- Image translation acceptance criteria (OCR, region filtering, coordinate
  overlays, Arabic rendering, cache reuse, touch passthrough, and responsive
  background work) remain unverified because the required image pipeline is not
  implemented.
- Build and test results for the current implementation batch are reported
  separately; this checklist is not a substitute for those results.
