package com.mickey.everythingdroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

object FileIcons {

    fun iconFor(name: String, isFolder: Boolean): ImageVector {
        if (isFolder) return Icons.Default.Folder
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "oga", "wma" -> Icons.Default.MusicNote
            "mid", "midi" -> Icons.Default.GraphicEq
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "wmv", "flv" -> Icons.Default.Videocam
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "tif", "tiff" -> Icons.Default.Image
            "pdf" -> Icons.Default.PictureAsPdf
            "doc", "docx", "odt", "rtf", "txt", "md", "log" -> Icons.Default.Description
            "ppt", "pptx", "odp" -> Icons.Default.Slideshow
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> Icons.Default.Archive
            "exe", "msi", "bat", "cmd", "dll" -> Icons.Default.Memory
            "apk" -> Icons.Default.Apps
            "kt", "kts", "java", "py", "js", "ts", "tsx", "json", "xml",
            "html", "htm", "css", "c", "cpp", "h", "cs", "rs", "go", "rb", "php" -> Icons.Default.Code
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }
}
