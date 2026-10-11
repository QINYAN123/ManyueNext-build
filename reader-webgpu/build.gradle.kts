import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.compose)
}

android {
    // JNI Kotlin packages stay upstream-compatible; Android manifest namespaces must be unique.
    namespace = "app.mihon.reader.webgpu"
    defaultConfig { consumerProguardFiles("proguard-rules.txt") }
}

// Compile the pinned, reviewable Kotlin fork while retaining byte-identical upstream JNI and
// embedded AndroidX WebGPU runtime. Never package two copies of ca.mpreg.webgpuviewer classes.
val pinnedUpstream by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val runtimeAar = layout.buildDirectory.file("verified-runtime/webgpuviewer-47-runtime.aar")
val runtimeJar = layout.buildDirectory.file("verified-runtime/webgpuviewer-47-runtime.jar")
val prepareVerifiedRuntime by tasks.registering {
    inputs.files(pinnedUpstream)
    outputs.files(runtimeAar, runtimeJar)
    doLast {
        val source = pinnedUpstream.singleFile
        val bytes = source.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == "ed4c38d88c7251ff5440182c5aeff979c7785c5fc66ca41f8bfd95b6cd22d910") {
            "WebGPU 47 runtime does not match the verified upstream AAR"
        }
        runtimeAar.get().asFile.parentFile.mkdirs()
        ZipOutputStream(runtimeAar.get().asFile.outputStream()).use { out ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val original = input.readBytes()
                    val data = if (name == "classes.jar") {
                        val jarBytes = ByteArrayOutputStream()
                        ZipOutputStream(jarBytes).use { jarOut ->
                            ZipInputStream(ByteArrayInputStream(original)).use { jarIn ->
                                var member = jarIn.nextEntry
                                while (member != null) {
                                    val keep = !member.name.startsWith("ca/mpreg/webgpuviewer/") &&
                                        member.name != "META-INF/library_release.kotlin_module"
                                    if (keep) {
                                        jarOut.putNextEntry(ZipEntry(member.name).apply { time = 0 })
                                        jarIn.copyTo(jarOut)
                                        jarOut.closeEntry()
                                    }
                                    jarIn.closeEntry()
                                    member = jarIn.nextEntry
                                }
                            }
                        }
                        jarBytes.toByteArray().also { runtimeJar.get().asFile.writeBytes(it) }
                    } else original
                    out.putNextEntry(ZipEntry(name).apply { time = 0 })
                    out.write(data)
                    out.closeEntry()
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
    }
}

dependencies {
    pinnedUpstream("${libs.webgpuviewer.get().module}:47@aar")
    // The app packages the verified AAR once. This module needs only its stripped classpath.
    compileOnly(files(runtimeJar).builtBy(prepareVerifiedRuntime))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.uiUtil)
}
