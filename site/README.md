# Documentation site

Static GitHub Pages documentation for `https://esko997.github.io/thortracker/`. The site has no JavaScript, framework, analytics, remote fonts, audio autoplay or build-time network content fetches. Python 3.12 and one hash-locked Python-Markdown wheel render repository content; the standard library handles templates, URL rewriting, local link checks and preview serving. No Gradle or native build is involved.

## Design

The identity comes from the instrument itself, not device marketing: an actual Pattern/Keyboard capture is the opening image, the four-colour mark reflects the first four Slate channel colours, and the typography uses the app's unmodified Noto Condensed and Mono fonts. There are no invented interface mockups or stock assets.

Design tokens: Paper `#f5f6f7`, Ink `#172531`, Slate `#181c22`, Pulse 1 `#7fd3c4`, Pulse 2 `#8fb8f0`, Wave `#c9a0f0`; Noise `#f09a8f` completes the channel mark. These come from `core/.../ui/Theme.kt`. Dark mode uses Slate surfaces with readable Contrast-derived secondary text; focus uses amber. Light mode uses Paper's darker link/focus colours rather than pastel text.

Layout: a left-aligned documentation bench, with a persistent contents rail on wide screens and an ordinary collapsible navigation disclosure on mobile. Reading measure stays under 80 characters. The screenshot is the distinctive element; headings, page contents and dividers do navigation work rather than simulate a dense tracker grid behind prose. The initial idea of a dark marketing landing page was rejected in favour of Paper by default, matching the app's own readable light theme. System dark preference is respected.

Native HTML disclosures, visible focus, a skip link, current-page indication and labelled keyboard-scrollable tables work without scripting. Animations from canonical Markdown become explicit GIF links rather than autoplaying inside the guide. Static screenshots keep their original descriptive alt text. Reduced-motion and print styles are included.

## Canonical content

`pages/*.md` contains navigation, introductory guidance and short reference indexes. Never paste whole manual sections here. An include directive names a canonical source and a unique heading prefix:

```html
<!-- include: README.md :: Controller reference -->
```

The generator renders the source once, selects the whole heading section (including its subsections), preserves its fragments and rewrites URLs relative to the original source. Missing or ambiguous includes fail the build. Selecting a nested section promotes its heading to H2 without changing its fragment. Source Markdown updates therefore appear automatically in the guide. Advertise controls only after integration: Android's controller dock, zoom and Sound panels are shipped; pattern-local mute remains deferred.

The full README, Deck README, UGE reference, audio-engine reference, backend-design history and verification record are also rendered, not forked. Long engineering records retain their historical claims and are labelled accordingly. They are references, not download recommendations. The short format/family indexes are editorial summaries grounded in the backend registry/export declarations and must be reviewed when those declarations change; the workflow watches that source tree.

Links to rendered documents map to their Pages routes. Relative media is copied under `assets/` with its repository path preserved; other source-file links point to GitHub. Raw HTML image/link attributes receive the same treatment. Unicode heading fragments and duplicate suffixes follow GitHub conventions. Old links differing only in repeated hyphen separators are rewritten only when the target is unique. All other missing fragments fail.

The build fails on missing local source targets, missing assets, duplicate IDs, broken generated page links/fragments, images without alt text, remote image/media assets and links that escape the configured base. It also checks CSS font URLs. External websites are not fetched or claimed to be checked; use the publication checklist below.

## Build and preview

From the repository root, with Python 3.12 installed:

```sh
python3.12 -m venv site/.venv
site/.venv/bin/python -m pip install --require-hashes --only-binary=:all: -r site/requirements.txt
site/.venv/bin/python -m unittest discover -s site -p 'test_*.py'
site/.venv/bin/python site/build.py --base /thortracker/
site/.venv/bin/python site/serve.py --base /thortracker/ --port 8000
```

Open `http://127.0.0.1:8000/thortracker/`. The preview binds loopback only and maps the exact Pages base path, not a root-only approximation. Stop with Ctrl+C. `site/_build` is generated and replaced on each build; do not hand-edit it. The committed sources and locked dependency are the reproducibility inputs; output has no build timestamp or random IDs. The font files and notices are copied unchanged from the repository.

For a root deployment, pass `--base /` to **both** commands. The production workflow intentionally builds `/thortracker/`. Renaming the repository or using a custom domain requires changing that workflow base and rechecking routes.

## Publication checklist (integration owner)

Verify changed pages with the production-base build and a real browser, then check the published site and release links. No Gradle/native compilation is needed for documentation-only changes.

1. Run the unit suite and production-base build. Build twice with unchanged inputs if checking reproducibility; compare the entire output tree, not just the landing page. Resolve every broken local URL/fragment rather than bypassing validation.
2. In a browser, open `/thortracker/` at 1440×1000, 844×390, 390×844 and 320×640. Inspect all eight guide pages and the manual. Confirm no page-wide horizontal overflow; scroll a control table and a code block independently. At 200% zoom, navigation and text must remain usable.
3. Use only Tab, Shift+Tab, Enter, Space and arrow keys: activate Skip to content; open/close documentation navigation and On this page; follow section links; focus/scroll a wide table. Confirm focus is visible, link names make sense, and navigation identifies the current page. Spot-check heading order and landmarks with an accessibility inspector or screen reader.
4. Check light and dark system themes. Enable reduced motion. No GIF should animate until its explicit link is activated; inspect both static hero and Brick screenshots. No animation, audio or video starts on page load.
5. Disable JavaScript and repeat navigation. All functionality is still available. In the network panel confirm requests stay local except explicit navigation to official GitHub/source links. Font loading must use `assets/deck/fonts/`; no analytics or remote asset origins should appear.
6. Check deep links by opening/reloading `/thortracker/downloads/#brick-hammer--nextui`, `/thortracker/controls/#controller-reference`, `/thortracker/files/#saving-and-recovery`, `/thortracker/manual/#microtuning-song-tuning-from-a-scala-scl-file`, and the OPL3/POKEY links on Sound systems. Open an animated example, use Back, and confirm the original section remains usable. Open `/thortracker/404.html` and follow its navigation.
7. Follow Downloads → official latest release. Check that APK, x86_64 AppImage and Brick zip are offered by the official release (not a candidate run). Check source, GPL, NOTICE, font licence, issue-template and external reference links. Review historical status notes in canonical README/audio/backend docs rather than rewriting them only in site output.
8. Reconcile README/PLAN/VERIFICATION changes with current release evidence. Merge only after shipped controls and export limits are reflected in canonical prose.
9. Repository **Settings → Pages → Build and deployment → Source: GitHub Actions**. Ensure the `github-pages` environment permits **main**, and any required approval is understood. No deploy key or personal token is required. Keep repository Actions allowed to use the pinned official actions.
10. Merge the source to main (parent only), or dispatch **Documentation Pages** on main. PRs build and upload the site but cannot deploy. Confirm the build and deploy jobs pass, then visit the environment URL `https://esko997.github.io/thortracker/` and repeat deep-link reload, mobile navigation, fonts/media, official downloads and 404 checks on the actual host. The existing `build.yml` is independent and unchanged.

## Maintaining the 1.0 public documentation

- Keep the README introduction and repository description multi-system and cross-platform. Canonical sections remain the source for detailed controls and limits.
- Keep Android touch-dock guidance current; do not confuse it with earlier archived interface proposals.
- Downloads distinguishes the SD-root Brick ZIP from the Pak-root `ThorTracker.pak.zip`. Preserve `/ThorTunez` during upgrades and do not claim Pak Store availability before the listing is accepted.
- Public screenshots use bundled demo songs only. `docs/NEXTUI_STORE_ENTRY.md` records capture provenance, captions and the draft listing.
- Version metadata lives in `app/build.gradle.kts` and root `pak.json`; package generation checks that metadata and runtime `BUILD-INFO` agree.
- Record actual publication/browser/artifact checks in release evidence. Never turn historical measurements into current all-device guarantees.
