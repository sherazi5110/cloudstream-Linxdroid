package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

/**
 * Parent of every plugin loader. Real implementations of the Android/app classes
 * plugins use come from android-shim; anything else under an Android package
 * (Fragment, View, R classes, ...) is generated here as an empty placeholder class,
 * so plugins that reference UI types for their settings screen still load and
 * their MainAPI providers work. Calling into such a placeholder fails, as expected.
 */
object AndroidStubLoader : ClassLoader("android-stubs", AndroidStubLoader::class.java.classLoader) {
    private val stubPrefixes = listOf("android.", "androidx.", "com.google.android.", "dalvik.", "com.lagradost.cloudstream3.")
    private val interfaceHints = listOf("Listener", "Callback", "Watcher", "Observer", "Provider\$Factory")

    val generated = sortedSetOf<String>()

    override fun findClass(name: String): Class<*> {
        if (stubPrefixes.none { name.startsWith(it) }) throw ClassNotFoundException(name)
        val isInterface = interfaceHints.any { name.substringAfterLast('.').contains(it) }
        val writer = ClassWriter(0)
        val internal = name.replace('.', '/')
        if (isInterface) {
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                internal, null, "java/lang/Object", null)
        } else {
            writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        synchronized(generated) { generated += name }
        return defineClass(name, bytes, 0, bytes.size)
    }
}

/**
 * One loader per plugin, like upstream's one PathClassLoader per .cs3.
 * Lookup order: host (library + shim) -> the plugin's own classes -> generated stubs.
 */
class PluginClassLoader(val jar: File, val original: File) :
    URLClassLoader(
        "plugin:${original.name}",
        listOfNotNull(jar, original.takeIf { it != jar }).map { it.toURI().toURL() }.toTypedArray(),
        PluginClassLoader::class.java.classLoader
    ) {

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = findResource(path)?.openStream()?.use { it.readBytes() }
            ?: return try { super.findClass(name) } catch (e: ClassNotFoundException) { AndroidStubLoader.loadClass(name) }
        // Most classes load as they are; only ones that read Compose's $stable fields are patched.
        if (!ComposeStable.mentions(bytes)) return super.findClass(name)
        val patched = ComposeStable.patch(bytes) { owner -> findResource("$owner.class") != null }
        val pkg = name.substringBeforeLast('.', "")
        if (pkg.isNotEmpty() && getDefinedPackage(pkg) == null) runCatching { definePackage(pkg, null, null, null, null, null, null, null) }
        return defineClass(name, patched, 0, patched.size, java.security.CodeSource(jar.toURI().toURL(), null as Array<java.security.CodeSigner>?))
    }

    /** Same detection the gradle plugin's CompileDexTask uses to write manifest.json. */
    fun findAnnotatedPluginClass(): String? = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }.firstNotNullOfOrNull { entry ->
            val node = ClassNode()
            ClassReader(zip.getInputStream(entry).readBytes()).accept(node, ClassReader.SKIP_CODE)
            val annotations = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()
            node.name.replace('/', '.').takeIf {
                annotations.any { a -> a.desc == "Lcom/lagradost/cloudstream3/plugins/CloudstreamPlugin;" }
            }
        }
    }
}

/**
 * Extensions built with Jetpack Compose read a hidden `$stable` field (Compose's stability flags) from the classes
 * they extend, e.g. `Plugin.$stable`. Stream Goat's own Plugin/MainAPI on the desktop are not built with Compose and
 * have no such field, so those extensions failed to load ("does not have member field 'int $stable'"). Reading
 * `$stable` from a class that isn't the extension's own becomes the constant 0 ("stable"), which is all Compose
 * would use it for; Compose never runs here.
 */
object ComposeStable {
    private val marker = "\$stable".toByteArray()

    fun mentions(bytes: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }

    /** [own] says whether a class (internal name) belongs to the extension itself. */
    fun patch(bytes: ByteArray, own: (String) -> Boolean): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, fieldDescriptor: String) {
                        if (opcode == Opcodes.GETSTATIC && fieldName == "\$stable" && fieldDescriptor == "I" && !own(owner)) {
                            super.visitInsn(Opcodes.ICONST_0)
                        } else super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor)
                    }
                }
        }, 0)
        return writer.toByteArray()
    }
}
