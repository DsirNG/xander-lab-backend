"""Minimal, dependency-free YAML writer.

The migration inventories must be regenerable on any machine without extra
pip installs, so we emit YAML ourselves instead of depending on PyYAML.
Only the subset actually used by the inventories is supported:

    dict / list / str / int / float / bool / None

Scalars are emitted as JSON scalars, which is a strict subset of YAML 1.2 and
therefore safe to parse back with any YAML reader.
"""

from __future__ import annotations

import json
import re

_PLAIN_SAFE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.\-/]*$")
_RESERVED = {
    "true", "false", "null", "yes", "no", "on", "off", "y", "n",
    "~", "", "-", "?", ":",
}


def _scalar(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value)
    # Keep simple identifiers readable, quote everything else.
    if _PLAIN_SAFE.match(text) and text.lower() not in _RESERVED and not text[0].isdigit():
        return text
    return json.dumps(text, ensure_ascii=False)


def _emit(value, indent: int, out: list[str]) -> None:
    pad = " " * indent
    if isinstance(value, dict):
        if not value:
            out.append(f"{pad}{{}}")
            return
        for key, item in value.items():
            key_text = _scalar(key)
            if isinstance(item, (dict, list)) and item:
                out.append(f"{pad}{key_text}:")
                _emit(item, indent + 2, out)
            elif isinstance(item, (dict, list)):
                out.append(f"{pad}{key_text}: {'{}' if isinstance(item, dict) else '[]'}")
            else:
                out.append(f"{pad}{key_text}: {_scalar(item)}")
    elif isinstance(value, list):
        if not value:
            out.append(f"{pad}[]")
            return
        for item in value:
            if isinstance(item, dict) and item:
                first_key = next(iter(item))
                first_val = item[first_key]
                if not isinstance(first_val, (dict, list)) or not first_val:
                    out.append(f"{pad}- {_scalar(first_key)}: {_scalar(first_val)}")
                    rest = dict(list(item.items())[1:])
                    if rest:
                        _emit(rest, indent + 2, out)
                    continue
                out.append(f"{pad}- {_scalar(first_key)}:")
                _emit(first_val, indent + 4, out)
                rest = dict(list(item.items())[1:])
                if rest:
                    _emit(rest, indent + 2, out)
            elif isinstance(item, list) and item:
                out.append(f"{pad}-")
                _emit(item, indent + 2, out)
            else:
                out.append(f"{pad}- {_scalar(item)}")
    else:
        out.append(f"{pad}{_scalar(value)}")


def dumps(value, header: str | None = None) -> str:
    out: list[str] = []
    if header:
        for line in header.strip().splitlines():
            out.append(f"# {line}" if line else "#")
        out.append("")
    _emit(value, 0, out)
    return "\n".join(out) + "\n"


def write(path, value, header: str | None = None) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(dumps(value, header))
