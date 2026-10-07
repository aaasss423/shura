"""Mihon/Keiyoushi-compatible protobuf repository index (``index.pb``).

The wire schema mirrors the one interpreted by Mihon's
``NetworkExtensionStore`` (see mihonapp/mihon
``data/src/main/java/mihon/data/extension/model/NetworkExtensionStore.kt``)
and produced by Keiyoushi's ``publish-repo.py``. Field numbers match the
Kotlin ``@ProtoNumber`` annotations so a real Mihon client can decode the file:

    Index {1 name, 2 badgeLabel, 3 signingKey, 4 contact, 5 extensionList}
      Contact {1 website, 2 discord}
      ExtensionList {1 repeated Extension}
      Extension {1 name, 2 packageName, 3 resources, 4 extensionLib,
                 5 versionCode, 6 versionName, 7 contentWarning,
                 8 repeated Source}
      Resources {1 apkUrl, 2 iconUrl}
      Source {1 id, 2 name, 3 language, 4 homeUrl, 5 mirrorUrls, 7 message}

Like Keiyoushi's production output the encoder emits the message followed by
gzip compression (deterministic, mtime=0); Mihon transparently decompresses
``index.pb`` before decoding (see ``decompressIfGzipped``).
"""
from __future__ import annotations

import gzip

from shura_core.publishing import protobuf as pb

# ContentWarning enum values used by Mihon/Keiyoushi (proto tag 7).
CONTENT_WARNING_SAFE = 1
CONTENT_WARNING_MIXED = 2
CONTENT_WARNING_NSFW = 3


def _source_message(source: dict) -> bytes:
    parts = [pb.varint_field(1, max(0, int(source.get("id", 0))))]
    for field, key in ((2, "name"), (3, "language"), (4, "home_url")):
        value = source.get(key, "") or ""
        parts.append(pb.string_field(field, str(value)))
    for mirror in source.get("mirror_urls", []) or []:
        parts.append(pb.string_field(5, str(mirror)))
    if source.get("message"):
        parts.append(pb.string_field(7, str(source["message"])))
    return b"".join(parts)


def _extension_message(entry: dict) -> bytes:
    apk_url = str(entry.get("apk_url") or ("apk/" + str(entry.get("apk", ""))))
    icon_url = str(entry.get("icon_url") or "")
    resources = pb.message_field(
        3, pb.string_field(1, apk_url) + pb.string_field(2, icon_url)
    )
    version = str(entry.get("version", ""))
    lib_parts = version.split(".")
    extension_lib = ".".join(lib_parts[:-1]) if len(lib_parts) >= 2 else version
    content_warning = CONTENT_WARNING_NSFW if entry.get("nsfw") else CONTENT_WARNING_SAFE
    parts = [
        pb.string_field(1, str(entry.get("name", ""))),
        pb.string_field(2, str(entry.get("pkg", ""))),
        resources,
        pb.string_field(4, extension_lib),
        pb.varint_field(5, max(0, int(entry.get("code", 0) or 0))),
        pb.string_field(6, version),
        pb.varint_field(7, content_warning),
    ]
    for source in entry.get("sources", []) or []:
        parts.append(pb.message_field(8, _source_message(source)))
    return b"".join(parts)


def encode_index(
    entries: list[dict],
    repo: str = "Shura",
    generated_at: int = 0,
    signing_key: str = "",
    badge_label: str | None = None,
    website: str = "",
    discord: str = "",
    gzip_output: bool = True,
) -> bytes:
    """Encode ``entries`` into a Mihon v2 ``Index`` message.

    ``generated_at`` is accepted for API compatibility only; the Mihon schema
    has no timestamp field. The ``index_v2`` field (102, external extension
    list URL) is deliberately not emitted: Shura writes the extension list
    inline so clients never need a second network hop.
    """
    name = str(repo)
    badge = badge_label if badge_label is not None else name
    contact = pb.message_field(4, pb.string_field(1, website))
    if discord:
        contact += pb.string_field(2, discord)
    extensions = b"".join(
        pb.message_field(1, _extension_message(entry)) for entry in entries
    )
    extension_list = pb.message_field(5, extensions)
    message = b"".join(
        [
            pb.string_field(1, name),
            pb.string_field(2, badge),
            pb.string_field(3, signing_key),
            contact,
            extension_list,
        ]
    )
    if not gzip_output:
        return message
    return gzip.compress(message, mtime=0)


def _text(value: bytes) -> str:
    return value.decode("utf-8", errors="replace")


def _maybe_gunzip(data: bytes) -> bytes:
    if data[:2] == b"\x1f\x8b":
        try:
            return gzip.decompress(data)
        except OSError:
            return data
    return data


def _decode_sources(raw_sources: list[bytes]) -> list[dict]:
    sources = []
    for raw in raw_sources:
        fields = pb.decode(raw)
        mirror_urls = [_text(item) for item in fields.get(5, [])]
        sources.append({
            "id": int(fields[1][0]) if fields.get(1) else 0,
            "name": _text(fields[2][0]) if fields.get(2) else "",
            "language": _text(fields[3][0]) if fields.get(3) else "",
            "home_url": _text(fields[4][0]) if fields.get(4) else "",
            "mirror_urls": mirror_urls,
            "message": _text(fields[7][0]) if fields.get(7) else "",
        })
    return sources


def decode_index(data: bytes) -> dict:
    top = pb.decode(_maybe_gunzip(data))
    repo = _text(top[1][0]) if top.get(1) else "Shura"
    badge_label = _text(top[2][0]) if top.get(2) else repo
    signing_key = _text(top[3][0]) if top.get(3) else ""
    website = ""
    discord = ""
    for raw in top.get(4, []):
        contact = pb.decode(raw)
        website = _text(contact[1][0]) if contact.get(1) else website
        discord = _text(contact[2][0]) if contact.get(2) else discord
    extensions = []
    for raw in top.get(5, []):
        list_fields = pb.decode(raw)
        for ext_raw in list_fields.get(1, []):
            fields = pb.decode(ext_raw)
            extensions.append({
                "name": _text(fields[1][0]) if fields.get(1) else "",
                "package_name": _text(fields[2][0]) if fields.get(2) else "",
                "apk_url": (_text(pb.decode(fields[3][0])[1][0])
                            if fields.get(3) and pb.decode(fields[3][0]).get(1) else ""),
                "icon_url": (_text(pb.decode(fields[3][0])[2][0])
                             if fields.get(3) and pb.decode(fields[3][0]).get(2) else ""),
                "extension_lib": _text(fields[4][0]) if fields.get(4) else "",
                "version_code": int(fields[5][0]) if fields.get(5) else 0,
                "version_name": _text(fields[6][0]) if fields.get(6) else "",
                "content_warning": int(fields[7][0]) if fields.get(7) else 0,
                "sources": _decode_sources(fields.get(8, [])),
            })
    return {
        "repo": repo,
        "badge_label": badge_label,
        "signing_key": signing_key,
        "website": website,
        "discord": discord,
        "extensions": extensions,
    }