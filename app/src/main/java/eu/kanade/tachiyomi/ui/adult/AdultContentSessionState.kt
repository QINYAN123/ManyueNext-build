package eu.kanade.tachiyomi.ui.adult

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Inject
@SingleIn(AppScope::class)
class AdultContentSessionState {
    private val mutableIsShown = MutableStateFlow(false)
    val isShown = mutableIsShown.asStateFlow()

    fun setShown(shown: Boolean) {
        mutableIsShown.value = shown
    }

    fun toggle() {
        mutableIsShown.update { !it }
    }
}
