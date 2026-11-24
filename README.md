# FF8 Save Editor Android

An Android companion project for editing Final Fantasy VIII save files, based on the save-format research and data model of [Hyne](https://github.com/myst6re/hyne) by myst6re.

## Current status

This repository starts the Android application shell. It can select a file through Android's Storage Access Framework, keep a private backup copy, and export a copy. **It does not yet parse or edit game data.** The Hyne save parser/editor must be ported and validated before editing is enabled.

## Compatibility target

- Final Fantasy VIII PlayStation save data, including DuckStation memory-card exports.
- No BIOS, game disc image, or emulator core is needed by this editor.
- Original save files are never overwritten by the initial import flow.

## Build

Open this project in Android Studio (current stable) and run the `app` configuration, or use the included GitHub Actions workflow.

## Upstream and license

Hyne is Copyright (C) its contributors and is distributed under GNU GPL v3. This project intends to reuse/adapt Hyne code and therefore retains GPL-3.0 licensing and attribution. See `COPYING.txt` for the license text and the upstream repository for original notices.

Upstream: https://github.com/myst6re/hyne
