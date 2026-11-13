package android.app

/** android.app.ActivityManager, for extensions that size their caches or work by the device's memory. */
open class ActivityManager {
    /** A desktop is never a low-memory device. */
    open fun isLowRamDevice(): Boolean = false
    open fun getMemoryClass(): Int = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt().coerceIn(128, 1024)
    open fun getLargeMemoryClass(): Int = getMemoryClass()
    open fun getMemoryInfo(outInfo: MemoryInfo) {
        val rt = Runtime.getRuntime()
        outInfo.totalMem = rt.maxMemory()
        outInfo.availMem = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        outInfo.threshold = outInfo.totalMem / 10
        outInfo.lowMemory = outInfo.availMem < outInfo.threshold
    }

    open class MemoryInfo {
        @JvmField var availMem: Long = 0
        @JvmField var totalMem: Long = 0
        @JvmField var threshold: Long = 0
        @JvmField var lowMemory: Boolean = false
    }

    companion object {
        @JvmStatic val instance = ActivityManager()
        @JvmStatic fun isUserAMonkey(): Boolean = false
    }
}
