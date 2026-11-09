# ayumi (vendored, unmodified)

Peter Sovietov's AY-3-8910 / YM2149 emulator, https://github.com/true-grue/ayumi, commit
`07c08b4874c359169e4a028edf73f046d8b763e2` (2021-11-24, "tone period fix, thanks to Volutar D"). MIT licence
(`LICENSE`).

| File | sha256 |
|---|---|
| `ayumi.c` | `8824bc343726aa9dc7e4172b14f0e9a0a06ed3f21766277ffb9686b07f19401d` |
| `ayumi.h` | `506e3b75d8269d670d74f75115d3b385ad0f1c232bc797391bd97cc074f17099` |
| `LICENSE` | `20bb643965c0ddd04e0b74aed83aa00c5ca0afb4805a681a60cbe4827cea66da` |

Thor Tracker uses it in two ways (docs/AUDIO_ENGINE.md, "AY-3-8910 / YM2149"): its measured DAC tables
(`AY_dac_table`, `YM_dac_table`) are copied as data into `app/src/main/cpp/ay/ay_psg.c`, and the native host test
links this copy as an independent emulator to compare the engine's output against. It is not built into the app.
