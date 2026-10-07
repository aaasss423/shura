"""Shura's own protobuf repository index (``index.pb``).

The schema is defined in ``index.proto`` in this package. It is deliberately
Shura's own format rather than a copy of Mihon/Keiyoushi's index, so that Shura
publishing stays independent of those projects. Compatibility adapters may
translate this format later.
"""
from __future__ import annotations

from shura_core.publishing import protobuf as pb

SCHEMA_VERSION = 1


def _extension_message(entry: dict) -> bytes:
    shura = entry.get("shura", {}) or {}
    parts = [
        pb.string_field(1, str(entry.get("name", ""))),
        pb.string_field(2, str(entry.get("pkg", ""))),
        pb.string_field(3, str(entry.get("apk", ""))),
        pb.string_field(4, str(entry.get("lang", "und"))),
        pb.string_field(5, str(entry.get("version", ""))),
        pb.varint_field(6, max(0, int(entry.get("code", 0) or 0))),
        pb.varint_field(7, 1 if entry.get("nsfw") else 0),
    ]
    for source in entry.get("sources", []) or []:
        parts.append(pb.string_field(8, str(source)))
    parts.extend([
        pb.string_field(9, str(shura.get("source_id", ""))),
        pb.string_field(10, str(shura.get("identity", ""))),
        pb.string_field(11, str(shura.get("artifact_sha256") or "")),
        pb.string_field(12, str(shura.get("signing_certificate_sha256") or "")),
        pb.varint_field(13, SCHEMA_VERSION),
    ])
    return b"".join(parts)


def encode_index(entries: list[dict], repo: str = "Shura", generated_at: int = 0) -> bytes:
    parts = [pb.string_field(1, repo)]
    if generated_at:
        parts.append(pb.varint_field(2, max(0, int(generated_at))))
    for entry in entries:
        parts.append(pb.message_field(3, _extension_message(entry)))
    return b"".join(parts)


def _text(value: bytes) -> str:
    return value.decode("utf-8", errors="replace")


def decode_index(data: bytes) -> dict:
    top = pb.decode(data)
    repo = _text(top[1][0]) if top.get(1) else "Shura"
    generated_at = int(top[2][0]) if top.get(2) else 0
    extensions = []
    for raw in top.get(3, []):
        fields = pb.decode(raw)
        sources = [_text(item) for item in fields.get(8, [])]
        extensions.append({
            "name": _text(fields[1][0]) if fields.get(1) else "",
            "pkg": _text(fields[2][0]) if fields.get(2) else "",
            "apk": _text(fields[3][0]) if fields.get(3) else "",
            "lang": _text(fields[4][0]) if fields.get(4) else "und",
            "version": _text(fields[5][0]) if fields.get(5) else "",
            "code": int(fields[6][0]) if fields.get(6) else 0,
            "nsfw": bool(fields[7][0]) if fields.get(7) else False,
            "sources": sources,
            "source_id": _text(fields[9][0]) if fields.get(9) else "",
            "identity": _text(fields[10][0]) if fields.get(10) else "",
            "artifact_sha256": _text(fields[11][0]) if fields.get(11) else "",
            "signing_certificate_sha256": _text(fields[12][0]) if fields.get(12) else "",
            "schema_version": int(fields[13][0]) if fields.get(13) else 0,
        })
    return {"repo": repo, "generated_at": generated_at, "extensions": extensions}
