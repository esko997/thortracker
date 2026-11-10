# Source & licences

Thor Tracker is **GPL-3.0-or-later** free software. The [source repository](https://github.com/esko997/thortracker) includes the application, native audio engines, offline tools and this documentation pipeline. [Official releases](https://github.com/esko997/thortracker/releases) provide the release tags and downloadable source archives alongside the application packages.

<!-- include: README.md :: License -->

## Full notices

- [GNU General Public License, version 3](../../LICENSE)
- [Packaged third-party NOTICE.txt](../../app/src/main/assets/NOTICE.txt)
- [Noto fonts: SIL Open Font License 1.1](../../deck/fonts/OFL.txt)
- [Tuning catalogue notices](../../app/src/main/assets/tunings/NOTICE)
- [DaMuSc source-data attribution](../../third_party/damusc/NOTICE)
- [Exact engine sources, pins and licence obligations](/audio-reference/#sources-pins-and-licences)

The site serves the same unmodified Noto Condensed and Noto Mono font files used by the Linux application. Their full OFL notice is included in the generated assets. Screenshots come from this repository and retain their captions and alternative text; there are no stock device mockups or proprietary firmware downloads.

The only site build dependency is [Python-Markdown](https://python-markdown.github.io/), pinned in the source with its wheel hash (BSD-3-Clause). Its code runs at build time, not in your browser. There is no JavaScript framework runtime, analytics service, tracking pixel or remote font request. The static host is GitHub Pages; ordinary hosting logs are governed by GitHub's policies, not application telemetry.

<!-- include: README.md :: Dependencies -->

## Build or contribute

Use the repository's [build-from-source instructions](/manual/#build-from-source) for the application and the [site maintainer instructions](../README.md) for these pages. The [verification record](/verification/) distinguishes automated checks from physical listening and hardware checks. Read current source and platform restrictions before running historical commands from an engineering record.
