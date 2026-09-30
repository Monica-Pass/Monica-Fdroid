package takagi.ru.monica.attachments.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentDownloadState
import takagi.ru.monica.attachments.model.AttachmentError
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.data.model.CardFaceAttachment

/**
 * 密码详情页的附件区块（只读 + 下载/预览/保存）。
 *
 * - LOCAL 附件打开即预览/分享；
 * - Bitwarden / KeePass 附件在 PENDING 状态下可点击触发下载，下载完成后自动预览。
 * - 预览对话框中提供"保存到设备"按钮，通过系统文件选择器导出。
 * - 无附件时不渲染，避免给没有附件的密码造成视觉噪音。
 */
@Composable
fun AttachmentsDetailSection(
    passwordId: Long,
    modifier: Modifier = Modifier,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    hideManagedCardFaces: Boolean = false
) = AttachmentsDetailSection(
    owner = AttachmentOwner.password(passwordId),
    modifier = modifier,
    bitwardenContext = bitwardenContext,
    keepassContext = keepassContext,
    hideManagedCardFaces = hideManagedCardFaces
)

@Composable
fun AttachmentsDetailSection(
    owner: AttachmentOwner,
    modifier: Modifier = Modifier,
    bitwardenContext: AttachmentFacade.BitwardenContext? = null,
    keepassContext: AttachmentFacade.KeePassContext? = null,
    excludedFileNames: Set<String> = emptySet(),
    includedFileNames: Set<String>? = null,
    displayFileNames: Map<String, String> = emptyMap(),
    hideManagedCardFaces: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val facade = remember(context) { AttachmentContainer.facade(context) }
    val allAttachments by facade.observe(owner).collectAsState(initial = emptyList())
    val attachments = allAttachments.filterNot {
        (includedFileNames != null && it.fileName !in includedFileNames) || it.fileName in excludedFileNames ||
            (hideManagedCardFaces && CardFaceAttachment.isManagedFileName(it.fileName))
    }

    if (attachments.isEmpty()) return

    // 预览对话框状态
    var previewState by remember { mutableStateOf<PreviewState?>(null) }

    // 保存到设备的文件选择器
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        val sourceUri = previewState?.uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    (context.contentResolver.openInputStream(sourceUri) ?: throw AttachmentError.IoError).use { input ->
                        (context.contentResolver.openOutputStream(targetUri) ?: throw AttachmentError.IoError).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.attachment_save_to_device) + " OK",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    resolveErrorMessage(context, e),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 预览对话框
    previewState?.let { state ->
        AttachmentPreviewDialog(
            previewUri = state.uri,
            mimeType = state.mimeType,
            fileName = state.fileName,
            onDismiss = { previewState = null },
            onOpenExternally = {
                val uri = state.uri
                previewState = null
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, state.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            },
            onSaveToDevice = {
                saveLauncher.launch(state.fileName)
            }
        )
    }

    AttachmentCarousel(
        attachments = attachments,
        displayNames = displayFileNames,
        modifier = modifier,
        readThumbnail = { attachment -> facade.readCachedImageBytes(attachment.id) },
        onOpen = { attachment ->
            scope.launch {
                if (attachment.downloadStateEnum == AttachmentDownloadState.FAILED) {
                    try {
                        facade.retryFailed(attachment.id, bitwardenContext, keepassContext)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        Toast.makeText(context, resolveErrorMessage(context, e), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                handleAttachmentClick(
                    facade, attachment, bitwardenContext, keepassContext,
                    onPreviewReady = { uri, mimeType, fileName ->
                        if (isPreviewable(mimeType)) {
                            previewState = PreviewState(uri, mimeType, displayFileNames[fileName] ?: fileName)
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    },
                    onError = { e -> Toast.makeText(context, resolveErrorMessage(context, e), Toast.LENGTH_SHORT).show() }
                )
            }
        }
    )
}

private data class PreviewState(
    val uri: Uri,
    val mimeType: String,
    val fileName: String
)

private suspend fun handleAttachmentClick(
    facade: AttachmentFacade,
    attachment: Attachment,
    bitwardenContext: AttachmentFacade.BitwardenContext?,
    keepassContext: AttachmentFacade.KeePassContext?,
    onPreviewReady: (Uri, String, String) -> Unit,
    onError: (Throwable) -> Unit
) {
    runCatching {
        val ready = facade.ensureDownloaded(
            attachment.id,
            bitwardenContext = bitwardenContext,
            keepassContext = keepassContext
        )
        val uri = facade.openForPreview(
            ready.id,
            bitwardenContext = bitwardenContext,
            keepassContext = keepassContext
        )
        onPreviewReady(uri, ready.mimeType, ready.fileName)
    }.onFailure { e ->
        onError(e)
    }
}

private fun isPreviewable(mimeType: String): Boolean {
    return mimeType.startsWith("image/") ||
        mimeType == "application/pdf" ||
        mimeType.startsWith("text/")
}

internal fun formatSecondary(attachment: Attachment): String {
    val sizeKb = (attachment.sizeBytes + 1023) / 1024
    val sizeText = when {
        attachment.sizeBytes <= 0 -> ""
        sizeKb >= 1024 -> "${sizeKb / 1024} MB"
        else -> "$sizeKb KB"
    }
    val sourceLabel = when (attachment.sourceEnum) {
        AttachmentSource.LOCAL -> "Local"
        AttachmentSource.BITWARDEN -> "Bitwarden"
        AttachmentSource.KEEPASS -> "KeePass"
    }
    return if (sizeText.isBlank()) sourceLabel else "$sourceLabel · $sizeText"
}

private fun resolveErrorMessage(context: android.content.Context, e: Throwable): String {
    return attachmentErrorMessage(context, e)
}
