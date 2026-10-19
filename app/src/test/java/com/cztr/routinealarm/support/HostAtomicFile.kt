package com.cztr.routinealarm.support

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android's POSIX rename replaces an existing destination. Java File.renameTo
 * on this Windows test host does not: API31 AtomicFile logs "Failed to rename"
 * and otherwise silently leaves old bytes. Only that host operation is adapted.
 * Linux CI and older Android implementations using .bak execute framework code.
 */
@Implements(AtomicFile::class)
class HostAtomicFile {
    @RealObject lateinit var real: AtomicFile

    @Implementation fun finishWrite(stream: FileOutputStream?) {
        val staged = File(real.baseFile.path + ".new")
        if (!System.getProperty("os.name").startsWith("Windows") || !staged.exists()) {
            Shadow.directlyOn<Any?, AtomicFile>(real, AtomicFile::class.java, "finishWrite",
                ClassParameter.from(FileOutputStream::class.java, stream))
            return
        }
        if (stream == null) return
        stream.flush(); stream.fd.sync(); stream.close()
        Files.move(staged.toPath(), real.baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
