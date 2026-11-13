"""Boundary checks for documentation transformation; no app/native build involved."""
import tempfile
import unittest
from pathlib import Path

from build import Builder, HEADINGS, LinkInventory, Page, Rewriter, render_markdown, section


class MarkdownTests(unittest.TestCase):
    def test_heading_fragments_and_duplicates(self):
        rendered = render_markdown("# A + B\n\n## Café / wave\n\n## Café / wave\n")
        self.assertEqual([h[2] for h in HEADINGS.finditer(rendered)], ["a--b", "café--wave", "café--wave-1"])

    def test_excerpt_keeps_children_not_sibling(self):
        rendered = render_markdown("# Manual\n\n## Start\n\nhello\n\n### Detail\n\ninside\n\n## End\n\nbye\n")
        excerpt = section(rendered, "Start")
        self.assertIn("inside", excerpt)
        self.assertNotIn("bye", excerpt)
        self.assertEqual([h[1] for h in HEADINGS.finditer(excerpt)], ["2", "3"])

    def test_excerpt_promotes_without_changing_fragment(self):
        excerpt = section(render_markdown("### Wave + Noise\n\nNotes."), "Wave + Noise")
        self.assertIn('<h2 id="wave--noise">', excerpt)

    def test_missing_or_ambiguous_heading_fails(self):
        text = render_markdown("## Sound one\n\n## Sound two")
        for prefix in ("Unknown", "Sound"):
            with self.assertRaises(ValueError):
                section(text, prefix)


class SiteTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.builder = Builder(self.root, "/thortracker/")

    def test_source_relative_links_use_pages_base(self):
        self.assertEqual(self.builder.resolve("../docs/MANUAL.md#install", "deck/README.md"), "/thortracker/manual/#install")
        self.assertEqual(self.builder.resolve("/files/#saving-and-recovery", "site/pages/index.md"), "/thortracker/files/#saving-and-recovery")
        self.assertEqual(self.builder.resolve("#install", "docs/MANUAL.md"), "/thortracker/manual/#install")
        self.assertEqual(self.builder.resolve("controls.md", "site/pages/index.md"), "/thortracker/controls/")

    def test_excerpt_links_stay_on_page_only_for_included_source_headings(self):
        manual = render_markdown(
            "## Links\n\n[local](#local--section) [elsewhere](#elsewhere) "
            "[explicit](MANUAL.md#local-section) [other source](#collision) "
            "[unicode](#caf%C3%A9)\n\n"
            "## Local section\n\n## Café\n\n## Elsewhere\n\n## Collision\n"
        )
        self.builder.documents["docs/MANUAL.md"] = manual
        page = Page("guide", "Guide", [
            ("docs/MANUAL.md", section(manual, "Links")),
            ("docs/MANUAL.md", section(manual, "Local section")),
            ("docs/MANUAL.md", section(manual, "Café")),
            ("deck/README.md", render_markdown("## Collision\n")),
        ])
        inventory = LinkInventory()
        inventory.feed(self.builder.render(page).partition("<article>")[2].partition("</article>")[0])
        self.assertIn("#local-section", inventory.links)
        self.assertIn("#caf%C3%A9", inventory.links)
        self.assertIn("/thortracker/manual/#elsewhere", inventory.links)
        self.assertIn("/thortracker/manual/#local-section", inventory.links)
        self.assertIn("/thortracker/manual/#collision", inventory.links)
        self.assertNotIn("#collision", inventory.links)

    def test_assets_resolve_against_their_source(self):
        url = self.builder.resolve("deck-pattern.png", "deck/README.md", asset=True)
        self.assertEqual(url, "/thortracker/assets/deck/deck-pattern.png")
        self.assertTrue((self.root / "assets/deck/deck-pattern.png").is_file())

    def test_root_deployment(self):
        builder = Builder(self.root, "/")
        self.assertEqual(builder.resolve("MANUAL.md#install", "docs/MANUAL.md"), "/manual/#install")

    def test_unsafe_and_missing_references_fail(self):
        for url in ("javascript:alert(1)", "../../../private", "not-a-real-image.png"):
            with self.assertRaises(ValueError):
                self.builder.resolve(url, "docs/MANUAL.md")
        with self.assertRaises(ValueError):
            self.builder.resolve("https://example.org/tracking.png", "docs/MANUAL.md", asset=True)

    def test_raw_images_and_tables_are_accessible(self):
        writer = Rewriter(self.builder, "docs/MANUAL.md")
        writer.feed('<img src="media/brick-paper.png" alt="Brick Pattern"><table><tr><th>Input</th></tr></table>')
        result = "".join(writer.parts)
        self.assertIn('/thortracker/assets/docs/media/brick-paper.png', result)
        self.assertIn('loading="lazy"', result)
        self.assertIn('tabindex="0"', result)
        self.assertIn('scope="col"', result)

    def test_animations_require_explicit_navigation(self):
        writer = Rewriter(self.builder, "docs/MANUAL.md")
        writer.feed('<img src="media/view-chords-r2.gif" alt="View switching">')
        result = "".join(writer.parts)
        self.assertNotIn("<img", result)
        self.assertIn("Open animated demonstration", result)

    def test_link_checker_rejects_broken_fragments(self):
        (self.root / "index.html").write_text('<h1 id="ok">Title</h1><a href="/thortracker/#missing">Broken</a>')
        with self.assertRaisesRegex(ValueError, "Missing fragment"):
            self.builder.check()
        (self.root / "index.html").write_text('<h1 id="ok">Title</h1><a href="/thortracker/#ok">Good</a>')
        self.builder.check()

    def test_link_checker_rejects_base_escape_and_missing_asset(self):
        for link in ("/outside/", "/thortracker/missing.png"):
            (self.root / "index.html").write_text(f'<a href="{link}">Broken</a>')
            with self.assertRaises(ValueError):
                self.builder.check()

    def test_duplicate_ids_fail(self):
        inventory = LinkInventory()
        with self.assertRaises(ValueError):
            inventory.feed('<h1 id="same">One</h1><h2 id="same">Two</h2>')


if __name__ == "__main__":
    unittest.main()
