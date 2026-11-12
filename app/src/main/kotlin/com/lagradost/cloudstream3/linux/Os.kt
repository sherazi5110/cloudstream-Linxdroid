package com.lagradost.cloudstream3.linux

import java.io.File

/**
 * The few things that differ between Linux and Windows: where data and downloads go, how a link or folder is
 * opened, where VLC is, and the words shown to the user. Everything else is the same code on both.
 */
object Os {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    /** "Linux" or "Windows", for texts such as "CloudStream for Windows". */
    val name: String = when { isWindows -> "Windows"; isMac -> "macOS"; else -> "Linux" }

    private val home: String get() = System.getProperty("user.home")

    /** Linux: ~/.local/share/cloudstream-linux (XDG). Windows: %APPDATA%\CloudStream. */
    fun defaultDataDir(): File = when {
        isWindows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "CloudStream")
        else -> File((System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"), "cloudstream-linux")
    }

    /** The user's Downloads folder (Linux: the XDG download folder, which the Flatpak may write to). */
    fun downloadsDir(): File {
        if (!isWindows) {
            runCatching {
                File(home, ".config/user-dirs.dirs").readLines()
                    .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }?.substringAfter('=')?.trim('"')
                    ?.replace("\$HOME", home)
            }.getOrNull()?.let { return File(it) }
        }
        return File(home, "Downloads")
    }

    /** Opens a web address or a file/folder with the system's default app. */
    fun open(target: String): Boolean = runCatching {
        when {
            // explorer opens folders and files; rundll32 opens links in the default browser without a console window.
            isWindows && (target.startsWith("http://") || target.startsWith("https://")) ->
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start()
            isWindows -> ProcessBuilder("explorer.exe", target).start()
            isMac -> ProcessBuilder("open", target).start()
            // xdg-open; inside the Flatpak this goes through the desktop portal.
            else -> ProcessBuilder("xdg-open", target).start()
        }
        true
    }.getOrDefault(false)

    /** What to do when VLC's libraries are missing. */
    val vlcHint: String get() = when {
        isWindows -> "VLC is included with CloudStream; reinstall CloudStream, or install VLC (64-bit) from videolan.org"
        else -> "sudo apt install libvlc5 vlc-plugin-base"
    }

    /**
     * VLC shipped inside the app (Windows: <app>\vlc with libvlc.dll and plugins\), next to the app's jars;
     * -Dcs.vlc.dir overrides it. Null when there's none (Linux uses the system's VLC).
     */
    fun bundledVlcDir(): File? {
        System.getProperty("cs.vlc.dir")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
        val jarDir = runCatching { File(Os::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
        return listOfNotNull(jarDir?.resolve("vlc"), jarDir?.parentFile?.resolve("vlc"))
            .firstOrNull { File(it, if (isWindows) "libvlc.dll" else "libvlc.so.5").isFile }
    }

    /**
     * Windows: makes cloudstreamrepo:// links (repository links on websites) open this app, for the current user
     * (HKCU, no admin rights), like the .desktop file does on Linux. Runs at start; does nothing if already set.
     */
    fun registerLinkHandler() {
        if (!isWindows) return
        val exe = System.getProperty("jpackage.app-path")?.takeIf { it.endsWith(".exe", true) } ?: return
        val key = "HKCU\\Software\\Classes\\cloudstreamrepo"
        val command = "\"$exe\" \"%1\""
        runCatching {
            val current = ProcessBuilder("reg", "query", "$key\\shell\\open\\command", "/ve").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            if (current.contains(exe, ignoreCase = true)) return
            fun reg(vararg args: String) = ProcessBuilder(listOf("reg", "add") + args.toList() + "/f").redirectErrorStream(true).start().waitFor()
            reg(key, "/ve", "/d", "URL:CloudStream repository")
            reg(key, "/v", "URL Protocol", "/d", "")
            reg("$key\\DefaultIcon", "/ve", "/d", "\"$exe\",0")
            reg("$key\\shell\\open\\command", "/ve", "/d", command)
        }.onFailure { System.err.println("W/os: couldn't register cloudstreamrepo:// links: $it") }
    }
}
