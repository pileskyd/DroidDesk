package com.droiddesk.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Core Linux runtime engine.
 *
 * Manages the proot-based Linux environment entirely within DroidDesk's
 * private data directory. No Termux dependency — we bundle our own proot
 * binary and bootstrap a standard Linux rootfs.
 *
 * Architecture:
 *   DroidDesk App (Kotlin)
 *     └─ ProcessBuilder → proot binary
 *         └─ rootfs (Ubuntu/Debian/Kali arm64)
 *             └─ Desktop Environment (XFCE/LXQt/MATE/KDE)
 *                 └─ Linux applications
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".droiddesk_bootstrapped"
    }

    private var prootProcess: Process? = null
    private var desktopProcess: Process? = null

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val binDir: File get() = File(baseDir, "usr/bin")
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(rootfsDir, "root")

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, "SETUP_COMPLETE").exists()
    }

    fun isRunning(): Boolean {
        return prootProcess?.isAlive == true
    }

    // ── Bootstrap ──

    /**
     * Set up the minimal bootstrap environment.
     * Extracts the bundled proot binary from APK assets and prepares directories.
     */
    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")

        // Create directory structure
        listOf(binDir, rootfsDir, tmpDir, homeDir).forEach { it.mkdirs() }

        // We no longer extract proot from assets.
        // It is bundled as jniLibs/arm64-v8a/libproot.so and installed by the package manager.
        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (!prootBin.exists()) {
            Log.e(TAG, "libproot.so not found in nativeLibraryDir!")
        } else {
            Log.i(TAG, "libproot.so found at ${prootBin.absolutePath}")
        }

        // Mark as bootstrapped
        File(baseDir, BOOTSTRAP_MARKER).writeText("DroidDesk v0.1.0\n")

        Log.i(TAG, "Bootstrap complete. Base: ${baseDir.absolutePath}")
    }

    // ── Session Management ──

    /**
     * Start a proot Linux session with the specified desktop environment.
     */
    fun startSession(desktopEnv: String = "xfce4") {
        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot start session — not bootstrapped")
            return
        }

        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
        val rootfs = rootfsDir.absolutePath

        // Build proot command
        val command = mutableListOf(
            prootBin,
            "--rootfs=$rootfs",
            "-w", "/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--root-id",          // Fake root (uid 0)
            "--kill-on-exit",     // Clean up child processes
            "--link2symlink",     // Handle hardlink limitations
            "/bin/bash", "--login"
        )

        Log.i(TAG, "Starting proot session: ${command.joinToString(" ")}")

        val env = buildEnvironment(desktopEnv)

        prootProcess = ProcessBuilder(command)
            .directory(rootfsDir)
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().putAll(env)
            }
            .start()

        Log.i(TAG, "Proot session started")
    }

    /**
     * Start the desktop environment inside the running proot session.
     */
    fun startDesktop(desktopEnv: String = "xfce4") {
        val startCmd = when (desktopEnv) {
            "xfce4" -> "startxfce4"
            "lxqt" -> "startlxqt"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            else -> "startxfce4"
        }

        executeCommand("export DISPLAY=:0 && dbus-run-session $startCmd &")
        Log.i(TAG, "Desktop environment started: $desktopEnv")
    }

    /**
     * Stop all running sessions.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")

        desktopProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        desktopProcess = null

        prootProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        prootProcess = null

        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    /**
     * Execute a command inside the proot environment and return output.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        val prootBin = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

        val fullCommand = listOf(
            prootBin,
            "--rootfs=${rootfsDir.absolutePath}",
            "-w", "/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=${tmpDir.absolutePath}:/tmp",
            "--root-id",
            "--link2symlink",
            "/bin/bash", "-c", command
        )

        return try {
            Log.d(TAG, "Executing command: $command")
            Log.d(TAG, "Full args: $fullCommand")
            
            val process = ProcessBuilder(fullCommand)
                .directory(rootfsDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(buildEnvironment())
                }
                .start()

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")
            Log.d(TAG, "Final output length: ${output.length}")
            output.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    // ── Environment ──

    private fun buildEnvironment(desktopEnv: String = ""): Map<String, String> {
        val env = mutableMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "HOSTNAME" to "droiddesk",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "SHELL" to "/bin/bash",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
            "PROOT_LOADER" to File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath,
            "PROOT_NO_SECCOMP" to "1",

            // GPU acceleration (Mesa/Turnip/Zink)
            "MESA_NO_ERROR" to "1",
            "MESA_GL_VERSION_OVERRIDE" to "4.6",
            "MESA_GLES_VERSION_OVERRIDE" to "3.2",
            "GALLIUM_DRIVER" to "zink",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "TU_DEBUG" to "noconform",
            "ZINK_DESCRIPTORS" to "lazy",
            "MESA_VK_WSI_PRESENT_MODE" to "immediate"
        )

        if (desktopEnv.isNotEmpty()) {
            env["XDG_SESSION_TYPE"] to "x11"
            env["XDG_RUNTIME_DIR"] to "/tmp/runtime-root"
            env["XDG_DATA_DIRS"] to "/usr/share:/usr/local/share"
            env["XDG_CONFIG_DIRS"] to "/etc/xdg"
            env["DISPLAY"] to ":0"
        }

        return env
    }

    // ── Asset Extraction ──
    // (Removed extractAsset since proot is now handled as a native library)
}
