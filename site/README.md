# Documentation site

Static documentation for https://esko997.github.io/thortracker/. Python 3.12 and a
hash-locked Python-Markdown dependency generate the site. It uses local fonts and
native HTML navigation, with no JavaScript or analytics.

## Content

`pages/*.md` contains the user guides. The manual, Linux reference and UGE reference
are generated from their repository sources. Engineering records, development plans
and verification logs remain in the source repository rather than becoming website pages.

Include a canonical section by its source path and unique heading prefix:

```html
<!-- include: README.md :: Controller reference -->
```

The generator preserves heading fragments and rewrites relative links. Bare fragment
links in excerpts stay on the current page when it includes the target heading from
the same source; otherwise they link to the full reference. Explicit document paths
always retain their destination. Missing or ambiguous includes fail the build.
Images are copied to `assets/`; other source links point to GitHub. GIFs appear as
explicit links rather than autoplaying.

Write product-facing instructions: describe behavior, requirements and limitations
concisely. Keep personal setup, review conversations and unpublished submission drafts
outside the public repository. Screenshot captions should identify the device or view.

## Build and preview

From the repository root:

```sh
python3.12 -m venv site/.venv
site/.venv/bin/python -m pip install --require-hashes --only-binary=:all: -r site/requirements.txt
site/.venv/bin/python -m unittest discover -s site -p 'test_*.py'
site/.venv/bin/python site/build.py --base /thortracker/
site/.venv/bin/python site/serve.py --base /thortracker/ --port 8000
```

Open `http://127.0.0.1:8000/thortracker/`. The preview binds loopback only.
`site/_build` is generated and replaced on each build. For a root deployment, pass
`--base /` to both commands.

The build validates local URLs, fragments, asset paths, image alt text and the
configured base path. External links require a separate check.

## Design and accessibility

The site uses the application's Noto Condensed and Mono fonts and Paper/Slate palette.
It follows system dark mode and includes reduced-motion and print styles. Native HTML
disclosures, visible keyboard focus, a skip link and scrollable tables work without scripts.

After changes, inspect affected pages on desktop and mobile. Check navigation, section
links, image loading and horizontal overflow. Test keyboard access when changing controls
or layout. Keep `site/style.css` font URLs local.

## Publication

The **Documentation Pages** workflow builds pull requests and deploys `main` through
GitHub Actions. Configure the repository's Pages source as **GitHub Actions**.
After deployment, check the published pages, release links and device installation
instructions. Application builds are independent of this workflow.
