package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.GetLanguagesWithSources
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.adult.service.AdultContentPreferences
import eu.kanade.tachiyomi.ui.adult.AdultContentSessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.source.model.Source
import java.util.SortedMap
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourcesFilterViewModel(
    private val preferences: SourcePreferences,
    private val getLanguagesWithSources: GetLanguagesWithSources,
    private val toggleSource: ToggleSource,
    private val toggleLanguage: ToggleLanguage,
    private val adultContentSessionState: AdultContentSessionState,
    private val adultContentPreferences: AdultContentPreferences,
) : ViewModel() {

    val state: StateFlow<State> = combine(
        getLanguagesWithSources.subscribe(),
        preferences.enabledLanguages.changes(),
        preferences.disabledSources.changes(),
        adultContentSessionState.isShown,
        adultContentPreferences.autoDetectionEnabled.changes(),
    ) { languagesWithSources, enabledLanguages, disabledSources, showAdultContent, autoDetectionEnabled ->
        State.Success(
            items = languagesWithSources,
            enabledLanguages = enabledLanguages,
            disabledSources = disabledSources,
            showAdultContent = showAdultContent,
            autoDetectionEnabled = autoDetectionEnabled,
        )
    }
        .catch<State> { throwable -> emit(State.Error(throwable = throwable)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State.Loading)

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun toggleLanguage(language: String) {
        toggleLanguage.await(language)
    }

    fun setAdultContentShown(shown: Boolean) = adultContentSessionState.setShown(shown)

    fun setAutoDetectionEnabled(enabled: Boolean) = adultContentPreferences.autoDetectionEnabled.set(enabled)

    sealed interface State {

        @Immutable
        data object Loading : State

        @Immutable
        data class Error(
            val throwable: Throwable,
        ) : State

        @Immutable
        data class Success(
        val items: SortedMap<String, List<Source>>,
        val enabledLanguages: Set<String>,
        val disabledSources: Set<String>,
        val showAdultContent: Boolean,
        val autoDetectionEnabled: Boolean,
    ) : State {

            val isEmpty: Boolean
                get() = items.isEmpty()
        }
    }
}
