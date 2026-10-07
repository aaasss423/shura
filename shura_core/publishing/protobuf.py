"""Minimal, dependency-free Protocol Buffers wire-format encoder/decoder.

Only the subset needed by Shura's own index schema is implemented: varints and
length-delimited fields. This is *not* a general protobuf runtime; it exists so
the repository can emit a deterministic ``index.pb`` without third-party
dependencies. Unknown fields are preserved by the decoder for forward
compatibility.
"""
from __future__ import annotations

WIRE_VARINT = 0
WIRE_LENGTH = 2


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint must be non-negative")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def decode_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ValueError("truncated varint")
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def field_key(field_number: int, wire_type: int) -> bytes:
    if field_number <= 0:
        raise ValueError("field number must be positive")
    return encode_varint((field_number << 3) | wire_type)


def varint_field(field_number: int, value: int) -> bytes:
    return field_key(field_number, WIRE_VARINT) + encode_varint(value)


def bytes_field(field_number: int, value: bytes) -> bytes:
    return field_key(field_number, WIRE_LENGTH) + encode_varint(len(value)) + value


def string_field(field_number: int, value: str) -> bytes:
    return bytes_field(field_number, value.encode("utf-8"))


def message_field(field_number: int, message: bytes) -> bytes:
    return bytes_field(field_number, message)


def decode(data: bytes) -> dict[int, list[object]]:
    """Decode a message into {field_number: [varint|bytes, ...]} preserving order."""
    fields: dict[int, list[object]] = {}
    offset = 0
    while offset < len(data):
        key, offset = decode_varint(data, offset)
        field_number, wire_type = key >> 3, key & 0x07
        if field_number == 0:
            raise ValueError("invalid field number 0")
        if wire_type == WIRE_VARINT:
            value, offset = decode_varint(data, offset)
        elif wire_type == WIRE_LENGTH:
            length, offset = decode_varint(data, offset)
            value = data[offset:offset + length]
            if len(value) != length:
                raise ValueError("truncated length-delimited field")
            offset += length
        else:
            raise ValueError(f"unsupported wire type {wire_type}")
        fields.setdefault(field_number, []).append(value)
    return fields


def decode_string(value: bytes, offset: int = 0) -> tuple[str, int]:
    return value[offset:].decode("utf-8"), len(value)
