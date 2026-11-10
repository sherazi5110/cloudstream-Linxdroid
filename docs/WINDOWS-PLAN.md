# CloudStream for Windows: plan

**Goal:** the same app as on Linux (look, profiles, extensions, Stremio add-ons, Live TV, downloads, VLC player,
everything in v7) as a normal Windows app: an installer (`.msi`) and a portable `.zip`, on the same GitHub release
as the Linux and Android files. One code base: the Linux app *is* the Windows app, with a few platform switches.

## Why this is a small step

The Linux app is Kotlin on Java 21 with JavaFX for the window and libVLC (through vlcj) for video. All three run on
Windows. Extensions are already translated from Android code to Java code, which is the same on every system.
What's Linux-specific is small and known:

| Where | Linux today | Windows |
|---|---|---|
| `Platform.kt` (data folder) | `~/.local/share/cloudstream-linux` (XDG) | `%APPDATA%\CloudStream` |
| `Downloads.kt` (downloads folder) | `XDG_DOWNLOAD_DIR` / `~/Downloads/CloudStream` | the user's Downloads folder (`%USERPROFILE%\Downloads\CloudStream`) |
| `Fx.kt`, `SettingsPage.kt`, `DownloadsUi.kt` (open a link or folder) | `xdg-open` | `java.awt.Desktop` / `explorer.exe` |
| `VlcVideo.kt` (find libVLC) | system `libvlc.so.5` (apt / Flatpak) | VLC bundled inside the app (`libvlc.dll` + `plugins\`), else an installed VLC |
| `app/build.gradle.kts` (JavaFX natives) | `linux` / `linux-aarch64` | `win` |
| Packaging | `.deb`, Flatpak, `.tar` (jpackage) | `.msi` + portable `.zip` (jpackage) |
| Link handler | `.desktop` file for `cloudstreamrepo://` | registry entry written by the installer |
| Texts | "sudo apt install libvlc5…", "Linux compatibility check" | Windows wording |

## Phases

### Phase 1: runs on Windows (from a zip)
1. **Platform switches** in one small file (`OsPaths`): data folder, downloads folder, "open file/link", the app name
   in texts. Linux behaviour stays exactly as it is.
2. **JavaFX for Windows:** pick the JavaFX platform per build (`win` on Windows).
3. **VLC inside the app:** CI downloads VLC 3.0.x for Windows (64-bit zip from VideoLAN, checksum-pinned) and puts
   `libvlc.dll`, `libvlccore.dll` and `plugins\` next to the app; `VlcVideo.kt` looks there first, then for an
   installed VLC (vlcj's discovery already knows the Windows registry/Program Files locations).
   About +60 MB unpacked; nobody has to install VLC separately.
4. **Windows details:** file names (no `: ? *` in download names; already cleaned), long paths, HiDPI scaling (JavaFX
   handles it; check the icon rail and player at 125/150 %), window icon, dark title bar.
5. **CI job `windows`** (GitHub's `windows-2022` runner): fetch upstream, build, run the 27 tests (the libVLC playback
   test uses the bundled VLC), make the portable `.zip` with jpackage's app image.

### Phase 2: proper installer
6. **`.msi` with jpackage** (WiX 3, preinstalled on `windows-2022`): Start-menu entry and desktop shortcut,
   per-user install (no admin prompt), uninstall from *Settings → Apps*, upgrades in place (fixed upgrade UUID),
   `cloudstreamrepo://` links registered.
7. **Release files:** `cloudstream-windows-<version>.msi` and `cloudstream-windows-<version>-portable.zip` attached to
   the same release; install notes added to `.github/release-install.md` and `INSTALL.md`.
8. **Update check** (already in the app) points Windows users at the `.msi`.

### Phase 3: polish (optional)
9. **Code signing.** Unsigned installers show "Windows protected your PC → More info → Run anyway" (SmartScreen).
   Options: leave it (explain in INSTALL.md), or apply to **SignPath Foundation** (free certificates for open-source
   projects; takes a review). Paid certificates cost ~$100–400/year.
10. **winget** package (`winget install CloudStream`) once releases are stable.
11. **Windows on ARM** (Snapdragon laptops): JavaFX and VLC both have ARM64 builds; a second CI job, later.
12. Windows-specific comforts: media keys and the taskbar thumbnail play/pause buttons in the player.

## What stays the same
Everything the user sees and every feature: profiles and kids mode, extensions and repositories, Stremio add-ons,
Live TV with the guide, TMDB/IMDb/AniList, downloads, skip intro, next-episode countdown, backup/restore (a Linux,
Android or Windows backup restores on any of them), the web-app server mode.

## How it gets tested
This development machine is Linux, so Windows is built and tested on GitHub's Windows runners: the same 27 automated
tests (extensions loading, sources, proxy, subtitles, downloads, libVLC playback) plus a screenshot run of the app
window on the runner. The first real check on your Windows PC is the portable zip from the CI run, before the
installer is published.

## Size and effort
- Download: about 110–130 MB (Java runtime ~45 MB, JavaFX ~25 MB, VLC ~60 MB, app ~15 MB), similar to the Flatpak.
- Work: phase 1 and 2 are one or two sessions like the v7 ones; phase 3 depends on the signing choice.

## Decisions for you
1. **Name:** keep "CloudStream" (installs as *CloudStream*, data in `%APPDATA%\CloudStream`).
2. **Installer type:** `.msi` (standard, recommended) or `.exe` setup.
3. **Signing:** start unsigned (with the SmartScreen note) and apply to SignPath later, or not at all.
4. **Version:** ship Windows as part of the next release (e.g. 7.1.0) for all platforms.
