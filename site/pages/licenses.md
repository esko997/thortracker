# Source & licences

Thor Tracker is **GPL-3.0-or-later** free software. The [source repository](https://github.com/esko997/thortracker) includes the application, native audio engines, offline tools and this documentation pipeline. [Official releases](https://github.com/esko997/thortracker/releases) provide the release tags and downloadable source archives alongside the application packages.

<!-- include: docs/MANUAL.md :: License -->

## Full notices

- [GNU General Public License, version 3](../../LICENSE)
- [Packaged third-party NOTICE.txt](../../app/src/main/assets/NOTICE.txt)
- [Noto fonts: SIL Open Font License 1.1](../../deck/fonts/OFL.txt)
- [Tuning catalogue notices](../../app/src/main/assets/tunings/NOTICE)
- [DaMuSc source-data attribution](../../third_party/damusc/NOTICE)
- [Engine sources, version pins and licence obligations](../../docs/AUDIO_ENGINE.md#sources-pins-and-licences)

The application and website use Noto Condensed and Noto Mono under the SIL Open Font License.

The only site build dependency is [Python-Markdown](https://python-markdown.github.io/), pinned in the source with its wheel hash (BSD-3-Clause). Its code runs at build time, not in your browser. There is no JavaScript framework runtime, analytics service, tracking pixel or remote font request. The static host is GitHub Pages; ordinary hosting logs are governed by GitHub's policies, not application telemetry.

<!-- include: docs/MANUAL.md :: Dependencies -->

## Build or contribute

See the [application build instructions](/manual/#build-from-source) and [documentation build instructions](../README.md) to contribute.
