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
#:
#: Necessary and nowhere near sufficient. Every catalogue serves logos, banners
#: and cover art as .jpg and .webp beside its real pages, so this only says "an
#: image"; ``page_image_prefix`` is what says "a page of this chapter".
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
    page_image_prefix: str = ""
    image_host: str = ""
    chapter_href: str = ""
    trust_page_heading: bool = True
    min_listing_group: int = 4
    version_code: int = 0
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
               entry_href: str | None = None,
               observed_page_images: list[str] | None = None,
               page_images_observed: bool | None = None,
               chapter_url: str = "",
               work_url: str = "",
               observed_chapter_urls: list[str] | None = None) -> ExtensionPlan:
    """Plan the extension for one accepted site.

    ``descriptor`` is the :class:`~shura_core.content.htmlsite.SiteDescriptor`
    evaluation produced. A descriptor that is not usable becomes a plan that is
    not publishable, carrying the descriptor's own reason rather than a new one.

    ``observed_hrefs`` are links actually seen on the site's own index. They are
    the evidence the entry shape is derived from: without them there is nothing
    to derive it from, and a shape invented here would compile and then match
    nothing at runtime. A descriptor with no observed hrefs is therefore refused
    rather than guessed at.

    ``observed_page_images`` is the evidence for the reading pages, and it is the
    gate that matters most. A catalogue that finds works and chapters but serves
    no page images in its HTML is a site whose reader is JavaScript; an
    extension for it would search perfectly and then show an empty chapter, which
    reads to the user as a broken manga rather than as an unsupported site.
    Pass ``page_images_observed=False`` when the evaluation saw a chapter page
    and found no images, and the site is refused with that reason.
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
    # Derived from the same function the publisher uses, so the index and the
    # artifact cannot disagree. Shura's index publishes a surrogate versionCode
    # of 10000 for "1.0"; an APK declaring 1 would make the reader compare its
    # own published index against an artifact that contradicts it.
    from ..publishing.repository import _version_code

    plan.version_code = _version_code(plan.version_name)
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
    observed_pages = list(observed_page_images or [])
    plan.chapter_href = derive_chapter_shape(work_url, list(observed_chapter_urls or []))
    plan.image_host = urlparse(observed_pages[0]).hostname or "" if observed_pages else ""
    if observed_pages:
        plan.page_image_prefix = derive_page_prefix(
            observed_pages, chapter_url or "", work_url or "")
    if page_images_observed is False:
        plan.blockers.append(
            "a chapter page was read and served no reading images, so this site "
            "needs an extraction mechanism that does not exist yet; it is not "
            "safe to publish an extension that would show empty chapters")
    elif observed_pages:
        if not any(re.search(plan.page_image, src) for src in observed_pages):
            plan.blockers.append(
                "the images this site served did not match the page image pattern, "
                "so reading pages could not be located")
        elif not plan.page_image_prefix:
            plan.blockers.append(
                "the images this site served share no path that identifies the "
                "chapter they belong to, so a cover or a logo could not be told "
                "apart from a reading page")
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


def derive_chapter_shape(work_url: str, child_urls: list[str]) -> str:
    """The href shape of a work's chapters, from the links under its own path.

    A work's page lives at one path and its chapters live beneath it, so
    "a link under the work's path" is a structural definition of "a chapter of
    this work" that does not depend on how the site words its links. The shape
    is digit-masked because chapter numbers vary while the route does not.
    """
    root = urlparse(work_url or "").path if "://" in (work_url or "") \
        else (work_url or "").split("?")[0]
    root = root.rstrip("/")
    if not root:
        return ""
    shapes: dict[str, int] = {}
    for href in child_urls or []:
        path = urlparse(href).path if "://" in href else href.split("?")[0]
        if not path.startswith(root + "/"):
            continue
        # The suffix under the work, not the whole URL: the work's own slug
        # carries an id whose digits would be masked into the pattern and make
        # it site-specific for no benefit. "chapter/\d+" is the part that says
        # "this is a chapter of that work".
        suffix = path[len(root) + 1:]
        shape = re.sub(r"\d+", lambda _: "\\d+", suffix)
        shapes[shape] = shapes.get(shape, 0) + 1
    if not shapes:
        return ""
    best, count = max(shapes.items(), key=lambda item: item[1])
    return best if count >= 2 else ""


def derive_page_prefix(page_images: list[str], chapter_url: str, work_url: str = "") -> str:
    """The path a chapter's own pages share, as a regex.

    This is the structural signal that a page belongs to *this* chapter rather
    than being site chrome. Real catalogues put a chapter's images under a path
    containing that chapter's own identity - the work slug and the chapter
    number - and put covers under a different path entirely. Observed on a live
    manhwa site::

        chapter: /comics/pick-up-a-grimoire-become-a-mage-1bb35e56/chapter/6
        pages:   cdn/.../asura-images/chapters/pick-up-a-grimoire-become-a-mage/6/f698fa.webp
        covers:  cdn/.../asura-images/covers/pick-up-a-grimoire-become-a-mage.<hash>-400.webp

    Only the pages share the ``chapters/<slug>/6/`` segment, so only they survive
    this prefix. Returns "" when the pages share no such segment, because a
    prefix that does not include the chapter's identity would admit the covers
    back in.
    """
    # A chapter's pages share one directory. Covers and logos do not: they are
    # scattered, and they sit under a different directory entirely. Grouping by
    # directory and taking the largest group is therefore the structural
    # statement "these are this chapter's pages", without needing to know what a
    # cover looks like on this particular site.
    by_directory: dict[str, list[str]] = {}
    for image in page_images:
        path = urlparse(image).path
        if not path:
            continue
        by_directory.setdefault(path.rsplit("/", 1)[0], []).append(path)
    if not by_directory:
        return ""
    directory, members = max(by_directory.items(), key=lambda item: len(item[1]))
    # A chapter is more than two pages long; a group that small is chrome.
    if len(members) < 3:
        return ""
    # The prefix has to name this chapter, or it proves nothing: a bare
    # "/images" would happily match every logo on the site.
    numbers = re.findall(r"\d+", urlparse(chapter_url).path)
    if numbers and numbers[-1] not in directory:
        return ""
    return _generalise(directory, chapter_url, work_url)


def _generalise(directory: str, chapter_url: str, work_url: str) -> str:
    r"""Turn one chapter's directory into a pattern that fits every chapter.

    An extension serves a *site*, not one chapter. The directory observed on the
    chapter that was read -
    ``/asura-images/chapters/pick-up-a-grimoire-become-a-mage/1`` - contains that
    one work's slug and that one chapter's number, so writing it into the
    extension verbatim would produce a source that reads exactly one chapter of
    exactly one work and reports every other as empty.

    So the segments that carry identity become patterns: the chapter number
    becomes ``\d+``, and the work slug becomes ``[^/]+``. Everything else stays
    literal, because that is the part that is the same on every work - and the
    part that separates pages from covers.
    """
    work_segment = ""
    if work_url:
        work_path = urlparse(work_url).path if "://" in work_url else work_url
        work_segment = work_path.rstrip("/").rsplit("/", 1)[-1]
    number = ""
    numbers = re.findall(r"\d+", urlparse(chapter_url).path)
    if numbers:
        number = numbers[-1]

    out = []
    for segment in directory.strip("/").split("/"):
        if number and segment == number:
            out.append("\\d+")
        elif work_segment and segment and (segment in work_segment or work_segment in segment):
            out.append("[^/]+")
        else:
            out.append(re.escape(segment))
    return "/" + "/".join(out)


def derive_entry_shape(hrefs: list[str]) -> str:
    """The href shape the index repeats, e.g. ``/node/\\d+`` for ``/node/4192``.

    The group is the *directory*, not a digit-masked path. Masking looks
    equivalent and is not: a catalogue whose slugs carry an id suffix -
    ``/comics/pick-up-a-grimoire-become-a-mage-1bb35e56`` - masks to a different
    string per work, so every link lands in a group of one and an index of
    forty works looks like navigation. It also generalises better: ``/node``
    matches ``/node/4192`` and any other chapter of that work, so a pattern
    written once keeps working as the site adds entries.

    This mirrors what the generated engine groups by, so the pattern written
    into the extension is the one the engine will actually match.
    """
    parents: dict[str, int] = {}
    for href in hrefs:
        path = urlparse(href).path if "://" in href else href.split("?")[0]
        parent = path.rsplit("/", 1)[0] if "/" in path else ""
        parents[parent] = parents.get(parent, 0) + 1
    if parents:
        best, count = max(parents.items(), key=lambda item: item[1])
        if count >= 4:
            return re.escape(best)
    # Nothing groups into a listing. "" rather than a best guess: a shape that
    # matches nothing at runtime reads to the reader as a source with no manga.
    return ""


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
        // Read from the links under a work's own path, so it does not depend on
        // how the site words them: one live catalogue writes "Chapter 6" against
        // "2 days ago" and the text alone reads as 62.
        chapterHref = Regex("""{chapter_href}"""),
        trustPageHeading = {trust_page_heading},
        pageImage = Regex("""{page_image}""", RegexOption.IGNORE_CASE),
        // Proof, not a guess: the path a chapter's own pages share, naming that
        // chapter's work and number. Covers and logos on the same page do not
        // match it, so they cannot be handed to the reader as page 1.
        pageImagePrefix = Regex("""{page_image_prefix}"""),
        // The reading images usually live on a different host than the pages
        // that link them, and the reader fetches these URLs.
        imageHost = "{image_host}",
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

// Publisher signing, supplied entirely by the build that holds the key.
//
// Shura refuses an extension whose certificate it does not trust, so an
// extension has to be signed by a publisher the app was built to trust. The
// keystore is never committed and the passwords never appear here: the build
// reads them from properties or the environment. Without them it falls back to
// the debug key, which produces an artifact the app will refuse - visibly,
// with a message naming the missing publisher, rather than one that quietly
// loads under somebody else's identity.
def publisherSigning() {{
    def ks = project.findProperty('shura.publisher.ks') ?: System.getenv('SHURA_PUBLISHER_KS')
    if (!ks) return null
    def storePass = project.findProperty('shura.publisher.storePass') ?: System.getenv('SHURA_PUBLISHER_STORE_PASS')
    def keyPass = project.findProperty('shura.publisher.keyPass') ?: System.getenv('SHURA_PUBLISHER_KEY_PASS')
    if (!storePass || !keyPass) {{
        throw new GradleException('shura.publisher.ks was given without its passwords; ' +
            'set them as -Pshura.publisher.storePass / -Pshura.publisher.keyPass, ' +
            'or SHURA_PUBLISHER_STORE_PASS / SHURA_PUBLISHER_KEY_PASS in the environment')
    }}
    return android.signingConfigs.create('publisher') {{
        storeFile file(ks)
        storePassword storePass
        keyAlias (project.findProperty('shura.publisher.alias') ?: System.getenv('SHURA_PUBLISHER_ALIAS') ?: 'publisher')
        keyPassword keyPass
    }}
}}
def publisher = publisherSigning()
if (publisher) {{
    android.buildTypes.debug.signingConfig publisher
    android.buildTypes.release.signingConfig publisher
}}

// GENERATED. The engine that reads this site lives in :shura-source-site and is
// compileOnly: the eu.kanade.tachiyomi.* ABI layer and okhttp are supplied by
// the host application at runtime, not bundled into the APK.
dependencies {{
    // Not bundled at runtime - the host supplies these types - but present for
    // the unit tests, which instantiate the source and check its ABI for real
    // rather than by reading the generated file.
    compileOnly project(':shura-extension-runtime')
    compileOnly project(':shura-source-site')
    testImplementation project(':shura-extension-runtime')
    testImplementation project(':shura-source-site')
    testImplementation 'junit:junit:4.13.2'
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
            chapter_marker=plan.chapter_marker, chapter_href=plan.chapter_href,
            trust_page_heading=str(plan.trust_page_heading).lower(),
            page_image=plan.page_image, page_image_prefix=plan.page_image_prefix,
            image_host=plan.image_host, cover_image=plan.cover_image),
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
    """Add a generated module to ``settings.gradle``, idempotently.

    The include line is parsed as a list and rewritten rather than patched with
    a regex. Patching it worked exactly once: the next call found a line that
    already ended in a generated module, matched a different boundary, and
    produced ``':x, ':y'`` - a settings file Gradle then refused to parse.
    """
    import re as _re

    settings_path = Path(settings_path)
    text = settings_path.read_text(encoding="utf-8")
    if _re.search(rf"['\"]{_re.escape(module_name)}['\"]", text):
        return False

    def rewrite(match):
        names = [n.strip().strip("'\"") for n in match.group(1).split(",")]
        names = [n for n in names if n]
        names.append(":" + module_name)
        return "include " + ", ".join(f"'{n}'" for n in names)

    updated, count = _re.subn(r"include\s+([^\n]+)", rewrite, text, count=1)
    if not count:
        return False
    if not updated.endswith("\n"):
        updated += "\n"
    settings_path.write_text(
        updated + f"project(':{module_name}').projectDir = file('{module_name}')\n",
        encoding="utf-8")
    return True
