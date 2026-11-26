package dev.brahmkshatriya.echo.common.providers

import dev.brahmkshatriya.echo.common.VideoExtension

/**
 * Interface to provide installed [VideoExtension]s to this extension
 */
interface VideoExtensionsProvider {
    /**
     * List of required [VideoExtension]s. If empty, all installed extensions will be provided.
     * If not empty, only the extensions with the specified ids will be provided
     */
    val requiredVideoExtensions: List<String>

    /**
     * Called when the extension is initialized,
     * to provide the [requiredVideoExtensions] to the extension.
     *
     * Also called when the extension list is updated.
     */
    fun setVideoExtensions(extensions: List<VideoExtension>)
}
