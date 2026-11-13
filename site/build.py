#!/usr/bin/env python3
"""Render canonical Markdown, then check every generated local link and fragment."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path
import posixpath
import re
import shutil
from urllib.parse import quote, unquote, urlsplit

import markdown

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"
REPO = "https://github.com/esko997/thortracker"
# Publish user and technical references; engineering work logs remain in the source repository.
DOCUMENTS = {
    "README.md": ("manual", "Complete manual"),
    "deck/README.md": ("deck-reference", "Linux reference"),
    "docs/UGE_EXPORT.md": ("uge-reference", "UGE reference"),
}
NAV = [
    ("", "Overview"), ("downloads", "Download & install"),
    ("getting-started", "User guide"), ("controls", "Input & controls"),
    ("systems", "Sound systems"), ("files", "Projects & exports"),
    ("troubleshooting", "Troubleshooting"), ("licenses", "Source & licences"),
    ("manual", "Complete manual"),
]
INCLUDE = re.compile(r"<!-- include: ([^\n]+?) :: ([^\n]+?) -->")
HEADINGS = re.compile(r'<h([1-6]) id="([^"]+)"[^>]*>(.*?)</h\1>', re.S)


def plain(text: str) -> str:
    return unescape(re.sub(r"<[^>]+>", "", text))


def slug(text: str) -> str:
    # GitHub-style Unicode heading fragments, not ASCII transliterations.
    return re.sub(r"[^\w\- ]", "", plain(text).lower()).replace(" ", "-")


def render_markdown(text: str) -> str:
    used: set[str] = set()

    def unique_slug(value: str, separator: str) -> str:
        base = slug(value)
        candidate = base
        number = 0
        while candidate in used:
            number += 1
            candidate = f"{base}-{number}"
        used.add(candidate)
        return candidate

    return markdown.markdown(
        text, extensions=["fenced_code", "tables", "toc", "sane_lists"],
        extension_configs={"toc": {"slugify": unique_slug}}, output_format="html",
    )


def section(document: str, prefix: str) -> str:
    headings = list(HEADINGS.finditer(document))
    matches = [h for h in headings if plain(h[3]).startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"Include heading must match exactly once: {prefix!r} ({len(matches)} matches)")
    start = matches[0]
    level = int(start[1])
    end = next((h.start() for h in headings if h.start() > start.start() and int(h[1]) <= level), len(document))
    result = document[start.start():end]
    # An excerpt is a section of its destination page, never a second page title.
    delta = 2 - level
    return re.sub(r"<(\/?)h([1-6])(\b)", lambda m: f"<{m[1]}h{min(6, int(m[2]) + delta)}{m[3]}", result)


@dataclass
class Page:
    route: str
    title: str
    chunks: list[tuple[str, str]]  # repository-relative source, rendered HTML


class Rewriter(HTMLParser):
    """Rewrite both Markdown-generated and raw HTML URLs, retaining semantic markup."""
    def __init__(self, build: Builder, source: str, local_fragments: frozenset[tuple[str, str]] = frozenset()):
        super().__init__(convert_charrefs=False)
        self.build = build
        self.source = source
        self.local_fragments = local_fragments
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        for key in ("href", "src", "poster"):
            if key in attrs:
                attrs[key] = self.build.resolve(attrs[key], self.source, asset=key != "href",
                                                local_fragments=self.local_fragments)
        if tag == "img":
            if not attrs.get("alt"):
                raise ValueError(f"Image without alternative text in {self.source}")
            if urlsplit(attrs.get("src", "")).path.lower().endswith(".gif"):
                self.parts.append(f'<a class="animation-link" href="{escape(attrs["src"], quote=True)}">'
                                  f'Open animated demonstration: {escape(attrs["alt"])} (GIF)</a>')
                return
            attrs.update(loading="lazy", decoding="async")
            attrs.pop("width", None)
            attrs.pop("height", None)
        if tag == "table":
            self.parts.append('<div class="table-scroll" tabindex="0" role="region" aria-label="Scrollable reference table">')
        if tag == "th" and "scope" not in attrs:
            attrs["scope"] = "col"
        if tag == "pre":
            attrs.update(tabindex="0", role="region")
            attrs["aria-label"] = "Scrollable code example"
        rendered = "".join(f' {k}="{escape(v, quote=True)}"' if v is not None else f" {k}" for k, v in attrs.items())
        self.parts.append(f"<{tag}{rendered}>")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        self.parts.append(f"</{tag}>")
        if tag == "table":
            self.parts.append("</div>")

    def handle_data(self, data):
        self.parts.append(data)

    def handle_entityref(self, name):
        self.parts.append(f"&{name};")

    def handle_charref(self, name):
        self.parts.append(f"&#{name};")

    def handle_comment(self, data):
        self.parts.append(f"<!--{data}-->")


class LinkInventory(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids: set[str] = set()
        self.links: list[str] = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            if attrs["id"] in self.ids:
                raise ValueError(f"Duplicate HTML id: {attrs['id']}")
            self.ids.add(attrs["id"])
        for key in ("href", "src", "poster"):
            if attrs.get(key):
                self.links.append(attrs[key])


class Builder:
    def __init__(self, output: Path, base: str):
        if not re.fullmatch(r"/(?:[A-Za-z0-9_-]+/)*", base):
            raise ValueError("Base must be an absolute directory path, e.g. /thortracker/ or /")
        self.output = output
        self.base = base
        self.documents = {path: render_markdown((ROOT / path).read_text()) for path in DOCUMENTS}
        self.copied: set[str] = set()

    def url(self, route: str = "") -> str:
        return self.base + (route.strip("/") + "/" if route else "")

    def copy(self, source: str) -> str:
        path = ROOT / source
        if not path.is_file():
            raise ValueError(f"Missing asset: {source}")
        if source not in self.copied:
            destination = self.output / "assets" / source
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
            self.copied.add(source)
        return self.base + "assets/" + quote(source, safe="/")

    def resolve(self, url: str, source: str, asset: bool = False, *,
                local_fragments: frozenset[tuple[str, str]] = frozenset()) -> str:
        parsed = urlsplit(unescape(url))
        if parsed.scheme or parsed.netloc:
            if parsed.scheme not in ("", "https", "http", "mailto"):
                raise ValueError(f"Unsupported URL scheme: {url}")
            if asset:
                raise ValueError(f"Remote asset is not allowed: {url}")
            return url
        if parsed.path.startswith("/"):
            # Site-authored root paths are relative to the deployment base, not github.io.
            return self.base + url.lstrip("/")
        target = posixpath.normpath(posixpath.join(posixpath.dirname(source), unquote(parsed.path))) if parsed.path else source
        if target.startswith("../"):
            raise ValueError(f"Link leaves repository: {source}: {url}")
        fragment = parsed.fragment
        if target in DOCUMENTS and fragment:
            ids = [h[2] for h in HEADINGS.finditer(self.documents[target])]
            if unquote(fragment) not in ids:
                # Older canonical links occasionally doubled punctuation separators (N163).
                # Only repair an unambiguous separator spelling, never a missing heading.
                normalized = re.sub("-+", "-", unquote(fragment))
                matches = [value for value in ids if re.sub("-+", "-", value) == normalized]
                if len(matches) == 1:
                    fragment = matches[0]
        if not asset and not parsed.path and not parsed.query and (source, unquote(fragment)) in local_fragments:
            return "#" + fragment
        suffix = ("?" + parsed.query if parsed.query else "") + ("#" + fragment if fragment else "")
        if target in DOCUMENTS:
            return self.url(DOCUMENTS[target][0]) + suffix
        if target.startswith("site/pages/") and target.endswith(".md"):
            route = Path(target).stem
            return self.url("" if route == "index" else route) + suffix
        path = ROOT / target
        if not path.exists():
            raise ValueError(f"Missing linked source: {source}: {url} ({target})")
        if asset or path.suffix.lower() in (".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".thor", ".uge", ".txt", ".ttf") or target == "LICENSE":
            return self.copy(target) + suffix
        kind = "tree" if path.is_dir() else "blob"
        return f"{REPO}/{kind}/main/{quote(target, safe='/')}" + suffix

    def pages(self) -> list[Page]:
        result = []
        for path in sorted((SITE / "pages").glob("*.md")):
            text = path.read_text()
            title = re.match(r"# (.+)\n", text)
            if not title:
                raise ValueError(f"Page needs one leading H1: {path}")
            chunks = []
            cursor = 0
            source = path.relative_to(ROOT).as_posix()
            for match in INCLUDE.finditer(text):
                chunks.append((source, render_markdown(text[cursor:match.start()])))
                included, heading = match.groups()
                if included not in self.documents:
                    raise ValueError(f"Unregistered canonical source: {included}")
                chunks.append((included, section(self.documents[included], heading)))
                cursor = match.end()
            chunks.append((source, render_markdown(text[cursor:])))
            result.append(Page("" if path.stem == "index" else path.stem, title[1], chunks))
        for path, (route, title) in DOCUMENTS.items():
            chunks = [(path, self.documents[path])]
            result.append(Page(route, title, chunks))
        return result

    def render(self, page: Page) -> str:
        chunks = []
        local_fragments = frozenset(
            (source, heading[2])
            for source, body in page.chunks
            for heading in HEADINGS.finditer(body)
        )
        for source, body in page.chunks:
            writer = Rewriter(self, source, local_fragments)
            writer.feed(body)
            writer.close()
            chunks.append("".join(writer.parts))
        body = "\n".join(chunks)
        headings = list(HEADINGS.finditer(body))
        toc = "".join(f'<li><a href="#{escape(h[2], quote=True)}">{escape(plain(h[3]))}</a></li>' for h in headings if int(h[1]) == 2)
        nav = "".join(f'<li><a href="{self.url(route)}"' + (' aria-current="page"' if route == page.route else '') + f'>{escape(title)}</a></li>' for route, title in NAV)
        sources = sorted({source for source, _ in page.chunks})
        credits = ", ".join(f'<a href="{REPO}/blob/main/{quote(source)}">{escape(source)}</a>' for source in sources)
        notice = ""
        if page.route in {v[0] for v in DOCUMENTS.values()}:
            notice = (f'<aside class="notice">Canonical repository reference. Historical proposals and candidate '
                      f'results retain their original context; consult <a href="{self.url("downloads")}">release notes</a> '
                      'for the version you install.</aside>')
        return f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="description" content="{escape(page.title, quote=True)} — Thor Tracker controller-first music tracker documentation.">
<meta name="color-scheme" content="light dark"><title>{escape(page.title)} · Thor Tracker</title>
<link rel="icon" href="{self.base}favicon.svg" type="image/svg+xml"><link rel="stylesheet" href="{self.base}style.css">
</head><body>
<a class="skip-link" href="#content">Skip to content</a>
<header class="masthead"><a class="brand" href="{self.url()}"><span class="channel-mark" aria-hidden="true"><i></i><i></i><i></i><i></i></span>Thor Tracker</a>
<a class="release-link" href="{REPO}/releases/latest">Get the release</a></header>
<div class="layout"><aside class="sidebar"><details class="navigation" open><summary>Documentation navigation</summary>
<nav aria-label="Documentation"><ul>{nav}</ul></nav></details>
<p class="sidebar-note">A controller. A pattern.<br>A sound chip.</p><a class="source-link" href="{REPO}">Source on GitHub</a></aside>
<main id="content" tabindex="-1">{notice}
{('<details class="contents"><summary>On this page</summary><ul>' + toc + '</ul></details>') if toc else ''}
<article>{body}</article>
<footer><p>Page sources: {credits}. Content is generated from these files, not copied into a separate manual.</p>
<p><a href="{self.url("licenses")}">GPL-3.0-or-later & third-party notices</a> · <a href="{self.url("troubleshooting")}">Report a problem</a></p>
<p>No analytics, remote fonts or tracking scripts. Screenshots show the app, not a web-based instrument.</p></footer></main></div>
</body></html>'''

    def check(self):
        inventories = {}
        for path in self.output.rglob("*.html"):
            inventory = LinkInventory()
            inventory.feed(path.read_text())
            inventories[path.resolve()] = inventory
        for path, inventory in inventories.items():
            for link in inventory.links:
                parsed = urlsplit(link)
                if parsed.scheme or parsed.netloc:
                    continue
                urlpath = unquote(parsed.path)
                if urlpath.startswith("/"):
                    if not urlpath.startswith(self.base):
                        raise ValueError(f"URL escapes deployment base: {path}: {link}")
                    target = self.output / urlpath[len(self.base):]
                else:
                    target = path.parent / urlpath if urlpath else path
                if target.is_dir():
                    target /= "index.html"
                target = target.resolve()
                if not target.is_relative_to(self.output.resolve()) or not target.is_file():
                    raise ValueError(f"Broken local link: {path}: {link}")
                if parsed.fragment and target in inventories and unquote(parsed.fragment) not in inventories[target].ids:
                    raise ValueError(f"Missing fragment: {path}: {link}")
        # CSS assets are not HTML links, so check each local url() explicitly too.
        for css in self.output.glob("*.css"):
            for reference in re.findall(r'url\([\'\"]?([^\)\'\"]+)', css.read_text()):
                if not (css.parent / reference).is_file():
                    raise ValueError(f"Missing CSS asset: {reference}")

    def build(self):
        # Only this fixed generated directory is ever replaced.
        if self.output.exists():
            shutil.rmtree(self.output)
        self.output.mkdir(parents=True)
        for name in ("style.css", "favicon.svg"):
            shutil.copyfile(SITE / name, self.output / name)
        for name in ("NotoSans-Condensed.ttf", "NotoSans-CondensedBold.ttf", "NotoSansMono-Regular.ttf", "OFL.txt"):
            self.copy("deck/fonts/" + name)
        for page in self.pages():
            directory = self.output / page.route
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "index.html").write_text(self.render(page), encoding="utf-8")
        missing = Page("404", "Page not found", [("site/pages/index.md", '<h1 id="not-found">Page not found</h1><p>Use the documentation navigation to find a guide, or return to the overview.</p>')])
        (self.output / "404.html").write_text(self.render(missing), encoding="utf-8")
        (self.output / ".nojekyll").touch()
        self.check()
        print(f"Built {len(list(self.output.rglob('*.html')))} pages; local URLs, fragments and assets checked. Base: {self.base}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="/thortracker/", help="URL directory including leading/trailing slash")
    args = parser.parse_args()
    Builder(SITE / "_build", args.base).build()


if __name__ == "__main__":
    main()
