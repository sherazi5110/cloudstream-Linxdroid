package android.content.res

import java.io.InputStream

/**
 * Plugins built with requiresResources get one of these. Android resource tables (strings, layouts) are not
 * decoded off Android, but files under assets/ are readable through [getAssets].
 */
open class Resources @JvmOverloads constructor(private val assetManager: AssetManager = AssetManager.global) {
    open fun getIdentifier(name: String, defType: String?, defPackage: String?): Int = 0
    open fun getString(id: Int): String = ""
    open fun getString(id: Int, vararg formatArgs: Any?): String = ""
    open fun getText(id: Int): CharSequence = getString(id)
    open fun getAssets(): AssetManager = assetManager
    open fun openRawResource(id: Int): InputStream = throw NotFoundException("Resource ID #0x${Integer.toHexString(id)}")

    open class NotFoundException(message: String? = null) : RuntimeException(message)

    companion object {
        /** The shared Context's resources: assets from every installed extension. */
        @JvmField val EMPTY = Resources()
    }
}
