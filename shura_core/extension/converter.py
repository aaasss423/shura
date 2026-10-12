"""Turn an accepted site into a real Shura extension.

This is the link the project was missing. Discovery proved a site, an operator
accepted it, and :mod:`shura_core.content.htmlsite` learned to *read* it - but
nothing turned that into an artifact the app can load, so an accepted site was
still inert on the phone.

The conversion is deliberately generic. Every generated extension is the same
three-part shape:

* a **spec** (:class:`~shura_core.extension.converter.ExtensionPlan`) holding only
  what varies about *this* site - its catalogue URL, the href shape its index
  was proven to use, how it marks a chapter in a title, how it serves a page
  image;
* a thin subclass of ``SiteCatalogueSource`` from ``android/shura-source-site``,
  which implements the eight methods Shura's ``ExtensionSourceAdapter`` calls;
* the manifest and build files the loader's existing gates read.

The reading logic is written once, in Kotlin, and never regenerated. That is
what makes this a mechanism rather than a folder of hand-written extensions: a
site that needs something the generic engine cannot express is a site that gets
a specialised implementation *alongside* the generic one, not a rewrite of it.

What is refused
---------------
A site with no proven catalogue URL, no proven href shape, or no proven page
image pattern does not get an extension. ``ExtensionPlan.blockers`` says why, and
:func:`build_plan` refuses rather than emitting a source that would return empty
results to the reader. An extension that silently finds nothing is worse than
one that was never published.
"""

from __future__ import annotations

import re
from pathlib import Path
from dataclasses import dataclass, field
from urllib.parse import urlparse

__all__ = ["ExtensionPlan", "build_plan", "render_module", "write_module"]


#: Matches the trailing "- Chapter 12" / "- الفصل 4" that marks a catalogue
#: entry as a chapter of the work named before it.
#:
#: This is a property of how manga catalogues are *written*, not of one site: it
#: is shipped in the generated spec so a site that phrases its chapters some
#: other way is described by data, not by a regenerated engine.
CHAPTER_MARKER = (
    r"(?:\s*[-–—|]\s*(?:[A-Za-z]{2,12}\s+|الفصل|فصل|حلقة|الفصـل)\s*#?\s*\d+(?:\.\d+)?\s*$)"
)

#: Matches the image sources a chapter page serves as its reading pages.
PAGE_IMAGE = r"\.(?:jpe?g|png|webp|gif)(?:\?|$)"

#: Matches cover art on a work page.
COVER_IMAGE = r"(?:cover|capa|thumb|poster)[^/]*\.(?:jpe?g|png|webp)(?:\?|$)"

#: The runtime ABI. Shura's loader refuses anything outside this range, so a
#: generated extension declares what it actually binds against rather than a
#: number chosen to pass the check.
EXTENSION_LIB = "1.0"

#: Matches the hrefs a catalogue index uses for its entries. Derived per site
#: from the repeating-shape detector, never from a guess.
ENTRY_HREF = r"/node/\d+"


@dataclass
class ExtensionPlan:
    """Everything needed to generate one extension, and why it may not be."""

    source_id: str
    name: str
    language: str
    host: str
    package: str = ""
    class_name: str = ""
    namespace: str = ""
    catalogue_url: str = ""
    entry_href: str = ENTRY_HREF
    chapter_marker: str = CHAPTER_MARKER
    page_image: str = PAGE_IMAGE
    cover_image: str = COVER_IMAGE
    trust_page_heading: bool = True
    min_listing_group: int = 4
    version_code: int = 1
    version_name: str = "1.0"
    module_name: str = ""
    blockers: list[str] = field(default_factory=list)

    @property
    def publishable(self) -> bool:
        return not self.blockers

    def reason(self) -> str:
        return "; ".join(self.blockers) if self.blockers else ""


def _package_id(host: str) -> str:
    """Java package segments from a host: ``www.mangaarabia.com`` -> ``mangaarabia``.

    A Kotlin package cannot contain a hyphen, so the module directory name and
    the package are derived separately rather than sharing one string.
    """
    import hashlib

    labels = host.lower().split(".")
    keep = [l for l in labels[1:-1] if re.fullmatch(r"[a-z][a-z0-9]*", l)]
    if not keep:
        keep = [l for l in labels if re.fullmatch(r"[a-z][a-z0-9]*", l)][:1]
    if not keep:
        # A non-Latin host has no ASCII to work with, so identity comes from a
        # digest rather than from a transliteration nobody verified.
        keep = ["site" + hashlib.sha1(host.lower().encode("utf-8")).hexdigest()[:8]]
    return ".".join(keep)


def source_number(source_id: str) -> int:
    """A stable positive number for a source id.

    The ABI's ``Source.id`` is a Long, and it is what Shura keys a loaded
    extension by. It has to be the same number every run, so it is derived from
    the source id rather than assigned by whatever order sites were discovered.
    """
    import hashlib

    digest = hashlib.sha1(source_id.encode("utf-8")).digest()
    return int.from_bytes(digest[:4], "big") & 0x7FFFFFFF


def _title(host: str, name: str) -> str:
    label = name or host
    return re.sub(r"[^A-Za-z0-9]+", " ", label).strip().title() or "Site"


def build_plan(descriptor, *, observed_hrefs: list[str] | None = None,
               page_image: str = PAGE_IMAGE,
               chapter_marker: str = CHAPTER_MARKER,
               entry_href: str | None = None) -> ExtensionPlan:
    """Plan the extension for one accepted site.

    ``descriptor`` is the :class:`~shura_core.content.htmlsite.SiteDescriptor`
    evaluation produced. A descriptor that is not usable becomes a plan that is
    not publishable, carrying the descriptor's own reason rather than a new one.

    ``observed_hrefs`` are links actually seen on the site's own index. They are
    the evidence the entry shape is derived from: without them there is nothing
    to derive it from, and a shape invented here would compile and then match
    nothing at runtime. A descriptor with no observed hrefs is therefore refused
    rather than guessed at.
    """
    usable, reason = descriptor.usable()
    host = descriptor.host or (urlparse(descriptor.catalogue_url).hostname or "")
    identifier = _package_id(host)
    module = identifier.replace(".", "-")
    plan = ExtensionPlan(
        source_id=descriptor.source_id,
        name=descriptor.name or host,
        language=descriptor.language or "und",
        host=host,
        package=f"app.shura.extension.{identifier}",
        class_name=f"{_title(host, descriptor.name)}Source".replace(" ", ""),
        namespace=f"app.shura.extension.{identifier}",
        module_name=f"shura-extension-{module}",
        catalogue_url=descriptor.catalogue_url,
        entry_href=entry_href or "",
        chapter_marker=chapter_marker,
        page_image=page_image,
    )
    if not usable:
        plan.blockers.append(reason or "the site is not usable")
    if not plan.catalogue_url:
        plan.blockers.append("no catalogue URL to read works from")
    if not plan.host:
        plan.blockers.append("no host to bind requests to")
    if not re.search(plan.entry_href, plan.catalogue_url or " ") and plan.catalogue_url:
        # The spec's href shape has to be able to match the site's own index,
        # otherwise the generated extension compiles and then finds nothing.
        plan.blockers.append(
            f"the proven entry shape {plan.entry_href!r} does not occur in {plan.catalogue_url}")
    if not plan.page_image:
        plan.blockers.append("no page image pattern was proven, so no reading pages")
    observed = list(observed_hrefs or [])
    if not observed:
        plan.blockers.append(
            "no entry link was observed on the index, so no href shape can be derived")
    elif not plan.entry_href:
        plan.entry_href = derive_entry_shape(observed)
        if not plan.entry_href:
            plan.blockers.append(
                "the observed links share no repeated shape, so the index is not a listing")
    return plan


def derive_entry_shape(hrefs: list[str]) -> str:
    """The href shape the index repeats, e.g. ``/node/\\d+`` for ``/node/4192``.

    This is the same "mask the digits and group" idea the crawler's
    repeating-shape detector uses, applied to the links evaluation observed, so
    the extension's pattern and the evidence that justified accepting the site
    come from one definition rather than two.
    """
    groups: dict[str, int] = {}
    for href in hrefs:
        shape = re.sub(r"\d+", lambda _: "\\d+", href.split("?")[0])
        # Everything that is not a digit is literal, and is escaped so that a
        # dot in "/index.php" matches a dot rather than any character.
        shape = re.sub(r"\\d\+", "\x00", shape)
        # "/" is not a metacharacter in a regex; escaping it would only make
        # the generated pattern harder to read.
        shape = re.sub(r"([.\\+*?\[\]^$(){}|])", r"\\\1", shape)
        shape = shape.replace("\x00", "\\d+")
        groups[shape] = groups.get(shape, 0) + 1
    if not groups:
        return ""
    best, count = max(groups.items(), key=lambda item: item[1])
    # One link is a menu entry, not a listing; the same threshold the crawler
    # uses to tell a listing from navigation.
    return best if count >= 4 else ""


_SOURCE_TEMPLATE = '''package {namespace}

import app.shura.source.site.SiteCatalogueSource
import app.shura.source.site.SiteSpec

/**
 * {name} as a Shura source.
 *
 * GENERATED by shura_core.extension.converter from a site an operator accepted.
 * Do not hand-edit: regenerate it, because the spec below is the evidence the
 * site was read with, and the reading logic it feeds lives in
 * android/shura-source-site.
 *
 * Everything below the spec is Shura's own `eu.kanade.tachiyomi` transitional
 * ABI; this class implements the eight methods Shura's ExtensionSourceAdapter
 * calls reflectively, and declares nothing else.
 */
class {class_name} : SiteCatalogueSource() {{

    override val baseUrl: String = "{catalogue_url}"

    override val spec: SiteSpec = SiteSpec(
        sourceId = {source_id},
        name = "{name}",
        lang = "{language}",
        catalogueUrl = "{catalogue_url}",
        // Proven by the repeating-shape detector over this site's own index.
        entryHref = Regex("""{entry_href}"""),
        minListingGroup = {min_listing_group},
        chapterMarker = Regex("""{chapter_marker}"""),
        trustPageHeading = {trust_page_heading},
        pageImage = Regex("""{page_image}""", RegexOption.IGNORE_CASE),
        coverImage = Regex("""{cover_image}""", RegexOption.IGNORE_CASE),
    )
}}
'''

_MANIFEST_TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Shura {name} Source">
        <!-- The two keys ShuraExtensionLoader/ExtensionLoader resolve. -->
        <meta-data android:name="tachiyomi.extension.class"
                   android:value="{class_fqn}" />
        <!-- A decimal literal, not a bare "1": aapt2 encodes a bare 1 as
             TYPE_INT_BOOLEAN, ApkManifestReader renders that as "true", and
             ShuraRepositoryClient then refuses the extension. -->
        <meta-data android:name="tachiyomix.extensionLib" android:value="{extension_lib}" />
    </application>
</manifest>
'''

_BUILD_TEMPLATE = '''plugins {{ id 'com.android.application'; id 'org.jetbrains.kotlin.android' }}

android {{
    namespace '{namespace}'
    compileSdk 34
    buildToolsVersion '35.0.0'
    defaultConfig {{
        applicationId '{package}'
        minSdk 26
        targetSdk 34
        versionCode {version_code}
        versionName '{version_name}'
    }}
    compileOptions {{ sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }}
    kotlinOptions {{ jvmTarget = '17' }}
    buildTypes {{
        debug {{ minifyEnabled false }}
        release {{ minifyEnabled false }}
    }}
}}

// GENERATED. The engine that reads this site lives in :shura-source-site and is
// compileOnly: the eu.kanade.tachiyomi.* ABI layer and okhttp are supplied by
// the host application at runtime, not bundled into the APK.
dependencies {{
    compileOnly project(':shura-extension-runtime')
    compileOnly project(':shura-source-site')
}}
'''


def render_module(plan: ExtensionPlan) -> dict[str, str]:
    """The files that make up the extension, as ``{relative path: contents}``.

    Paths are relative to the module directory so a caller can place the module
    wherever its tree wants it.
    """
    if not plan.publishable:
        raise ValueError(f"refusing to generate {plan.source_id}: {plan.reason()}")
    source_path = (f"src/main/java/{plan.namespace.replace('.', '/')}/{plan.class_name}.kt")
    return {
        "build.gradle": _BUILD_TEMPLATE.format(**_fmt(plan)),
        "src/main/AndroidManifest.xml": _MANIFEST_TEMPLATE.format(
            name=plan.name, class_fqn=f"{plan.namespace}.{plan.class_name}",
            extension_lib=EXTENSION_LIB),
        source_path: _SOURCE_TEMPLATE.format(
            namespace=plan.namespace, class_name=plan.class_name, name=plan.name,
            source_id=source_number(plan.source_id), language=plan.language,
            catalogue_url=plan.catalogue_url, entry_href=plan.entry_href,
            min_listing_group=plan.min_listing_group,
            chapter_marker=plan.chapter_marker,
            trust_page_heading=str(plan.trust_page_heading).lower(),
            page_image=plan.page_image, cover_image=plan.cover_image),
    }


def _fmt(plan: ExtensionPlan) -> dict:
    return dict(plan.__dict__)


def write_module(root, plan: ExtensionPlan) -> Path:
    """Write the extension module into ``root`` and register it with Gradle."""
    root = Path(root)
    module_dir = root / (plan.module_name or f"shura-extension-{plan.package.rsplit('.', 1)[-1]}")
    files = render_module(plan)
    for relative, contents in files.items():
        target = module_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")
    settings = root / "settings.gradle"
    if settings.exists():
        register_module(settings, module_dir.name)
    return module_dir


def register_module(settings_path, module_name: str) -> bool:
    """Add a generated module to ``settings.gradle``, idempotently."""
    from pathlib import Path

    settings_path = Path(settings_path)
    text = settings_path.read_text(encoding="utf-8")
    if f":{module_name}'" in text or f":{module_name}\"" in text:
        return False
    import re as _re

    # MULTILINE matters: the include line is not the last line of the file, so
    # without it the substitution silently matched nothing and Gradle reported
    # "project with path ':x' could not be found" instead of a missing include.
    # Group 1 stops *before* the closing quote and the replacement supplies its
    # own, so emitting group 2 as well would double it and break the file.
    text, changed = _re.subn(r"(include\s+[^\n]*?)(['\"])$",
                             lambda m: m.group(1) + f", ':{module_name}'",
                             text, count=1, flags=_re.MULTILINE)
    if not changed:
        return False
    settings_path.write_text(text + f"project(':{module_name}').projectDir = "
                             f"file('{module_name}')\n", encoding="utf-8")
    return True
