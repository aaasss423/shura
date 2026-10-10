// Transitional ABI layer for extensions published by Shura Core.
//
// WHY THIS EXISTS
// Shura's Core publishes extension APKs whose binary contract is the
// Tachiyomi extension ABI. Those APKs are not self-contained: their DEX binds
// against eu.kanade.tachiyomi.* types that live in the host application, not in
// the APK. Loading one therefore requires the host to supply those types.
//
// This package is that supply. It is a transitional layer, scoped to exactly
// what the real artifacts reference, and it is not a Shura architecture goal:
// the signatures here exist to satisfy an existing binary contract so Shura can
// run the extensions its own Core publishes. A future native Shura Extension
// Runtime will sit alongside this and needs none of it.
//
// The exact required surface was read out of the shipped APK's DEX
// (eu.kanade.tachiyomi.source.model.{SManga, SChapter, Page, MangasPage, Filter,
// FilterList}, eu.kanade.tachiyomi.source.online.HttpSource,
// eu.kanade.tachiyomi.network.{NetworkHelper, RequestsKt}) and is asserted by
// ExtensionAbiContractTest against that artifact. Do not add members "just in
// case": each one is a promise to keep compatible with a published APK.
//
// SECURITY
// Nothing here is a sandbox. Code loaded through the extension class loader
// runs with Shura's own permissions, identity and filesystem access. A separate
// ClassLoader separates class *names*, nothing more. Signature verification
// proves who built an artifact; it is not a defence against what that artifact
// does once loaded.
package eu.kanade.tachiyomi.source

/**
 * Root of the transitional source hierarchy.
 *
 * Extensions extend [eu.kanade.tachiyomi.source.online.HttpSource]; Shura's own
 * source interface is `app.shura.source.ShuraSource` and is adapted onto this.
 */
interface Source {
    val id: Long
    val name: String
    val lang: String
}

/** A source that supports search + detail, which is the search path Shura loads first. */
interface CatalogueSource : Source

/** A source with a preferences screen. Extensions implementing this need `androidx.preference`. */
interface ConfigurableSource : Source