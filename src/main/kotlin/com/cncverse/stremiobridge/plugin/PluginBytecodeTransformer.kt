package com.cncverse.stremiobridge.plugin

import com.cncverse.stremiobridge.state.ServerState
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rewrites dex2jar output so plugins run safely on the desktop JVM:
 *
 *  1. Android UI calls (widgets, views, dialogs, androidx) are replaced with
 *     stack-neutral no-ops that return default values — settings dialogs and
 *     other UI code paths become inert instead of crashing with
 *     NoSuchMethodError. The stub classes still exist for verification.
 *  2. System.exit / Runtime.exit / Runtime.exec are redirected to guards so a
 *     plugin can't kill or fork from the bridge process.
 *  3. dex2jar renames inline-class methods ("box-impl" → "box_impl"); the
 *     names are restored so Kotlin linkage works.
 *
 * Android classes we provide FUNCTIONAL stubs for (util, text, net, os,
 * content, graphics core) are deliberately NOT neutered.
 */
object PluginBytecodeTransformer {

    fun transform(jarFile: File) {
        val tempFile = File(jarFile.absolutePath + ".tmp")
        ZipInputStream(FileInputStream(jarFile)).use { zis ->
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                var entry = zis.nextEntry
                while (entry != null) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    val bytes = zis.readBytes()
                    if (entry.name.endsWith(".class")) {
                        val writer = ClassWriter(0)
                        val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
                            override fun visitMethod(
                                access: Int,
                                name: String,
                                descriptor: String?,
                                signature: String?,
                                exceptions: Array<out String>?,
                            ): MethodVisitor {
                                val mv = super.visitMethod(access, fixMethodName(name), descriptor, signature, exceptions)
                                return object : MethodVisitor(Opcodes.ASM9, mv) {

                                    private fun isUIClass(owner: String): Boolean {
                                        // NOTE: android.view.{View,ViewGroup,LayoutInflater} stay
                                        // FUNCTIONAL (needed for inflate + findViewById during
                                        // settings discovery); widgets/dialogs/androidx/material
                                        // calls are neutered.
                                        if (owner == "android/webkit/CookieManager") return false
                                        return owner.startsWith("android/widget/") ||
                                            owner.startsWith("android/app/") ||
                                            owner.startsWith("androidx/") ||
                                            owner.startsWith("com/google/android/material/") ||
                                            owner.startsWith("android/graphics/drawable/") ||
                                            owner.startsWith("android/webkit/")
                                    }

                                    private fun pushDefault(type: Type) {
                                        when (type.sort) {
                                            Type.VOID -> {}
                                            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> super.visitInsn(Opcodes.ICONST_0)
                                            Type.FLOAT -> super.visitInsn(Opcodes.FCONST_0)
                                            Type.LONG -> super.visitInsn(Opcodes.LCONST_0)
                                            Type.DOUBLE -> super.visitInsn(Opcodes.DCONST_0)
                                            Type.ARRAY, Type.OBJECT -> super.visitInsn(Opcodes.ACONST_NULL)
                                        }
                                    }

                                    private fun popType(type: Type) {
                                        if (type.size == 2) {
                                            super.visitInsn(Opcodes.POP2)
                                        } else {
                                            super.visitInsn(Opcodes.POP)
                                        }
                                    }

                                    override fun visitMethodInsn(
                                        opcode: Int,
                                        owner: String,
                                        methodName: String,
                                        descriptor: String,
                                        isInterface: Boolean,
                                    ) {
                                        // Keep Context.getSharedPreferences working (functional prefs stub).
                                        // Plugins may call it on Activity / AppCompatActivity / Context
                                        // references — all inherit the method from Context, so exempt
                                        // `getSharedPreferences` on ANY owner within the neutered set.
                                        if (methodName != "<init>" && isUIClass(owner) &&
                                            methodName != "getSharedPreferences" &&
                                            methodName != "getApplicationContext"
                                        ) {
                                            val argTypes = Type.getArgumentTypes(descriptor)
                                            val retType = Type.getReturnType(descriptor)

                                            for (i in argTypes.indices.reversed()) {
                                                popType(argTypes[i])
                                            }
                                            if (opcode != Opcodes.INVOKESTATIC) {
                                                super.visitInsn(Opcodes.POP)
                                            }
                                            pushDefault(retType)
                                            return
                                        }

                                        var newOpcode = opcode
                                        var newOwner = owner
                                        var newDesc = descriptor

                                        if (owner == "java/lang/Runtime" && (methodName == "exec" || methodName == "loadLibrary" || methodName == "load" || methodName == "exit" || methodName == "halt")) {
                                            newOpcode = Opcodes.INVOKESTATIC
                                            newOwner = "com/cncverse/stremiobridge/plugin/PluginSandboxStubs"
                                            newDesc = descriptor.replace("(", "(Ljava/lang/Runtime;")
                                        } else if (owner == "java/lang/System" && (methodName == "exit" || methodName == "loadLibrary" || methodName == "load" || methodName == "setSecurityManager")) {
                                            // static call — same descriptor, just a different owner
                                            newOwner = "com/cncverse/stremiobridge/plugin/PluginSandboxStubs"
                                        }

                                        super.visitMethodInsn(newOpcode, newOwner, fixMethodName(methodName), newDesc, isInterface)
                                    }

                                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                                        if (isUIClass(owner)) {
                                            val type = Type.getType(descriptor)
                                            when (opcode) {
                                                Opcodes.GETSTATIC -> pushDefault(type)
                                                Opcodes.PUTSTATIC -> popType(type)
                                                Opcodes.GETFIELD -> {
                                                    super.visitInsn(Opcodes.POP)
                                                    pushDefault(type)
                                                }
                                                Opcodes.PUTFIELD -> {
                                                    popType(type)
                                                    super.visitInsn(Opcodes.POP)
                                                }
                                            }
                                            return
                                        }
                                        super.visitFieldInsn(opcode, owner, name, descriptor)
                                    }
                                }
                            }
                        }
                        ClassReader(bytes).accept(visitor, 0)
                        zos.write(writer.toByteArray())
                    } else {
                        zos.write(bytes)
                    }
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        jarFile.delete()
        tempFile.renameTo(jarFile)
    }

    /** dex2jar mangles inline-class method names by replacing '-' with '_'. */
    private fun fixMethodName(name: String): String {
        return when (name) {
            "constructor_impl" -> "constructor-impl"
            "box_impl" -> "box-impl"
            "unbox_impl" -> "unbox-impl"
            "isSuccess_impl" -> "isSuccess-impl"
            "isFailure_impl" -> "isFailure-impl"
            "getOrNull_impl" -> "getOrNull-impl"
            "exceptionOrNull_impl" -> "exceptionOrNull-impl"
            else -> name
        }
    }
}

/** Guards injected by [PluginBytecodeTransformer] — keep the signatures referenced there. */
object PluginSandboxStubs {
    @JvmStatic
    fun exec(runtime: Runtime, command: String): Process? {
        ServerState.warn("Plugin sandbox: blocked Runtime.exec($command)")
        return null
    }

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>): Process? {
        ServerState.warn("Plugin sandbox: blocked Runtime.exec(${cmdarray.joinToString().take(80)})")
        return null
    }

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>, envp: Array<String>?): Process? = exec(runtime, cmdarray)

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>, envp: Array<String>?, dir: File?): Process? = exec(runtime, cmdarray)

    @JvmStatic
    fun exec(runtime: Runtime, command: String, envp: Array<String>?): Process? = exec(runtime, command)

    @JvmStatic
    fun exec(runtime: Runtime, command: String, envp: Array<String>?, dir: File?): Process? = exec(runtime, command)

    @JvmStatic
    fun loadLibrary(runtime: Runtime, libname: String) {
        ServerState.warn("Plugin sandbox: blocked Runtime.loadLibrary($libname)")
    }

    @JvmStatic
    fun load(runtime: Runtime, filename: String) {
        ServerState.warn("Plugin sandbox: blocked Runtime.load($filename)")
    }

    @JvmStatic
    fun exit(runtime: Runtime, status: Int) {
        ServerState.warn("Plugin sandbox: blocked Runtime.exit($status)")
    }

    @JvmStatic
    fun halt(runtime: Runtime, status: Int) {
        ServerState.warn("Plugin sandbox: blocked Runtime.halt($status)")
    }

    @JvmStatic
    fun exit(status: Int) {
        ServerState.warn("Plugin sandbox: blocked System.exit($status)")
    }

    @JvmStatic
    fun loadLibrary(libname: String) {
        ServerState.warn("Plugin sandbox: blocked System.loadLibrary($libname)")
    }

    @JvmStatic
    fun load(filename: String) {
        ServerState.warn("Plugin sandbox: blocked System.load($filename)")
    }

    @JvmStatic
    fun setSecurityManager(s: SecurityManager?) {
        ServerState.warn("Plugin sandbox: blocked System.setSecurityManager()")
    }
}
