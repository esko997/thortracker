#!/usr/bin/env python3
"""Serve a built site at the same base path as GitHub Pages, on loopback only."""
import argparse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import re
from urllib.parse import urlsplit

from build import SITE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="/thortracker/")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    # Validate without building or loading canonical documents.
    if not re.fullmatch(r"/(?:[A-Za-z0-9_-]+/)*", args.base):
        parser.error("base must be an absolute directory path")
    directory = SITE / "_build"
    if not (directory / "index.html").is_file():
        parser.error("build first with site/build.py using the same --base")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(directory), **kw)

        def send_head(self):
            path = urlsplit(self.path).path
            if path in ("/", args.base.rstrip("/")) and path != args.base:
                self.send_response(302)
                self.send_header("Location", args.base)
                self.end_headers()
                return None
            if not path.startswith(args.base):
                self.send_error(404)
                return None
            return super().send_head()

        def translate_path(self, path):
            # SimpleHTTPRequestHandler still performs its normal traversal normalization.
            return super().translate_path("/" + path[len(args.base):])

    print(f"Preview: http://127.0.0.1:{args.port}{args.base}")
    with ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
