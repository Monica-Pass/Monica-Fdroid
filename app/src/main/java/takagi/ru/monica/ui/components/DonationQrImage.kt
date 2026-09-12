package takagi.ru.monica.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R

@Composable
internal fun rememberDonationQrBitmap(@DrawableRes resource: Int): Bitmap? {
    val context = LocalContext.current
    val state = remember(context, resource) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(state) {
        state.value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(context.resources, resource, BitmapFactory.Options().apply { inScaled = false })
        }
    }
    return state.value
}

/** Both QR pages save the original image, with the same legacy permission handling. */
@Composable
internal fun rememberDonationQrSaver(
    onRequestPermission: ((String, (Boolean) -> Unit) -> Unit)? = null,
): (Bitmap) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    fun save(bitmap: Bitmap) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { saveDonationQrToGallery(context, bitmap) } }
                val message = result.fold(
                    onSuccess = { context.getString(R.string.qr_code_saved) },
                    onFailure = { context.getString(R.string.save_failed_with_error, it.localizedMessage.orEmpty()) },
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }
    val permissionResult: (Boolean) -> Unit = { granted ->
        val bitmap = pendingBitmap
        pendingBitmap = null
        if (granted && bitmap != null) save(bitmap)
        else if (!granted) Toast.makeText(context, R.string.storage_permission_needed, Toast.LENGTH_SHORT).show()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), permissionResult)
    return { bitmap ->
        if (!saving && pendingBitmap == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                save(bitmap)
            } else {
                pendingBitmap = bitmap
                if (onRequestPermission != null) onRequestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionResult)
                else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
}

/** Call on an IO worker. A failed write must not leave an empty picture in the gallery. */
internal fun saveDonationQrToGallery(context: Context, bitmap: Bitmap): Uri {
    val filename = "Monica_QR_${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Monica")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).resolve("Monica")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create image directory")
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, directory.resolve(filename).absolutePath)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Cannot create gallery image")
    try {
        val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot open gallery image")
        stream.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot encode image") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}
