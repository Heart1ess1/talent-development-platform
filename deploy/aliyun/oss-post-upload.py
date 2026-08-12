#!/usr/bin/env python3
"""Upload one file using the size-bound OSS POST policy returned by the application."""

import json
import mimetypes
import pathlib
import sys
import urllib.request
import uuid


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: oss-post-upload.py <file>")
    ticket = json.load(sys.stdin)["data"]
    if ticket.get("method") != "POST":
        raise SystemExit("upload ticket is not an OSS POST policy")
    source = pathlib.Path(sys.argv[1])
    fields = ticket.get("formFields") or {}
    boundary = "----talent-platform-" + uuid.uuid4().hex
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
            str(value).encode(), b"\r\n",
        ])
    content_type = fields.get("Content-Type") or mimetypes.guess_type(source.name)[0]
    content_type = content_type or "application/octet-stream"
    chunks.extend([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{source.name}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        source.read_bytes(), b"\r\n", f"--{boundary}--\r\n".encode(),
    ])
    request = urllib.request.Request(ticket["uploadUrl"], data=b"".join(chunks), method="POST")
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status not in (200, 201, 204):
            raise SystemExit(f"OSS upload failed: HTTP {response.status}")
    print(fields["key"])


if __name__ == "__main__":
    main()
