package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.adult.service.AdultContentPreferences
import eu.kanade.domain.adult.service.SourceContentRatingStore
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.ui.adult.AdultContentPolicy
import eu.kanade.tachiyomi.ui.adult.AdultContentSessionState
import eu.kanade.tachiyomi.ui.adult.SourceContentWarningResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Pin
import tachiyomi.domain.source.model.Source
import mihon.domain.extension.model.ContentWarning
import mihon.domain.content.model.ContentRatingOverride
import java.util.TreeMap
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourcesViewModel(
    private val getEnabledSources: GetEnabledSources,
    private val toggleSource: ToggleSource,
    private val toggleSourcePin: ToggleSourcePin,
    private val sourceContentRatingStore: SourceContentRatingStore,
    private val adultContentSessionState: AdultContentSessionState,
    private val adultContentPreferences: AdultContentPreferences,
    private val sourceContentWarningResolver: SourceContentWarningResolver,
) : ViewModel() {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val enabledSources = combine(
        getEnabledSources.subscribe(),
        adultContentSessionState.isShown,
        adultContentPreferences.autoDetectionEnabled.changes(),
        sourceContentWarningResolver.warnings,
    ) { sources, showAdultContent, autoDetectionEnabled, warningMap ->
        toSourceUiModels(sources, showAdultContent, autoDetectionEnabled, warningMap)
    }.catch {
        logcat(LogPriority.ERROR, it)
        _events.send(Event.FailedFetchingSources)
    }

    val state: StateFlow<State> = combine(
        enabledSources,
        dialog,
    ) { items, dialog ->
        State(dialog = dialog, isLoading = false, items = items)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private fun toSourceUiModels(
        sources: List<Source>,
        showAdultContent: Boolean,
        autoDetectionEnabled: Boolean,
        warningMap: Map<Long, ContentWarning>,
    ): List<SourceUiModel> {
        val visibleSources = sources.filter { source ->
            AdultContentPolicy.shouldShow(
                showAdult = showAdultContent,
                autoDetectionEnabled = autoDetectionEnabled,
                override = sourceContentRatingStore.get(source.id),
                detectedAdult = AdultContentPolicy.isAdult(
                    sourceId = source.id,
                    warning = warningMap[source.id] ?: ContentWarning.SAFE,
                    genres = null,
                ),
            )
        }
        val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
            // Sources without a lang defined will be placed at the end
            when {
                d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                d1 == "" && d2 != "" -> 1
                d2 == "" && d1 != "" -> -1
                else -> d1.compareTo(d2)
            }
        }
        val byLang = visibleSources.groupByTo(map) {
            when {
                it.isUsedLast -> LAST_USED_KEY
                Pin.Actual in it.pin -> PINNED_KEY
                else -> it.lang
            }
        }

        return byLang.flatMap {
            listOf(
                SourceUiModel.Header(it.key),
                *it.value.map { source ->
                    SourceUiModel.Item(source)
                }.toTypedArray(),
            )
        }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    fun contentRating(source: Source): ContentRatingOverride = sourceContentRatingStore.get(source.id)

    fun setContentRating(source: Source, override: ContentRatingOverride) {
        sourceContentRatingStore.set(source.id, override)
    }

    fun showSourceDialog(source: Source) {
        dialog.update { Dialog(source) }
    }

    fun closeDialog() {
        dialog.update { null }
    }

    sealed interface Event {
        data object FailedFetchingSources : Event
    }

    data class Dialog(val source: Source)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: List<SourceUiModel> = listOf(),
    ) {
        val isEmpty = items.isEmpty()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}
