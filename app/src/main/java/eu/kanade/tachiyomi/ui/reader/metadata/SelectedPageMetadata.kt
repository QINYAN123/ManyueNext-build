package eu.kanade.tachiyomi.ui.reader.metadata

data class SelectedPageMetadata(
    val selectedKey: String,
    val info: SourceImageInfo,
) {
    fun select(key: String, initial: SourceImageInfo) = SelectedPageMetadata(key, initial)

    fun update(key: String, newInfo: SourceImageInfo) =
        if (key == selectedKey) copy(info = newInfo) else this
}
