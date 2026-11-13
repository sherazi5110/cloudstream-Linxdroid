package com.lagradost.cloudstream3.linux

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Extensions built with Jetpack Compose read `Plugin.$stable` (e.g. MovieBoxProvider from the Phisher repo); the
 * desktop's Plugin has no such field. They must load anyway, and their own `$stable` fields must keep working.
 */
class ComposeStableTest {
    /** An extension class: `static int host()` reads Plugin.$stable, `static int own()` reads its own $stable (= 7). */
    private fun extensionJar(dir: File): File {
        val w = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        w.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "ext/MyPlugin", null, "com/lagradost/cloudstream3/plugins/Plugin", null)
        w.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "\$stable", "I", null, 7).visitEnd()
        w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "com/lagradost/cloudstream3/plugins/Plugin", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        for ((name, owner) in listOf("host" to "com/lagradost/cloudstream3/plugins/Plugin", "own" to "ext/MyPlugin")) {
            w.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()I", null, null).apply {
                visitCode(); visitFieldInsn(Opcodes.GETSTATIC, owner, "\$stable", "I"); visitInsn(Opcodes.IRETURN)
                visitMaxs(0, 0); visitEnd()
            }
        }
        w.visitEnd()
        return File(dir, "ext.jar").apply {
            ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry("ext/MyPlugin.class")); z.write(w.toByteArray()); z.closeEntry() }
        }
    }

    @Test
    fun `extensions built with Compose load`() {
        val dir = Files.createTempDirectory("stable").toFile()
        try {
            val jar = extensionJar(dir)
            val loader = PluginClassLoader(jar, jar)
            val cls = loader.loadClass("ext.MyPlugin")
            assertEquals(0, cls.getMethod("host").invoke(null))
            assertEquals(7, cls.getMethod("own").invoke(null))
            // It is a real Plugin (the app can create and load it).
            assert(com.lagradost.cloudstream3.plugins.Plugin::class.java.isAssignableFrom(cls))
            cls.getDeclaredConstructor().newInstance()
            loader.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
