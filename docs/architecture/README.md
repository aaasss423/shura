# Architecture

`models` defines source/candidate/publication records. `sources` validates operator configuration. `discovery` proposes untrusted sources, while `crawler` extracts candidate metadata. `security.network` owns outbound policy; `pipeline` validates and downloads artifacts; `security.artifacts` verifies bounded APK structure, package and certificate. `state` persists eligibility, provenance, pending, review/quarantine and ledger records. `quality` assigns source scores; `publishing` emits repository JSON after explicit acceptance. `scheduling`, `observability`, and `notifications` are separate operational boundaries.

Android `shura-source-api` describes source contracts and `shura-source-host` hosts source adapters/download policy. The Android app has a local image chapter reader. `translation` is separate and disabled by default. No Mihon runtime dependency is present.
