package eu.kanade.tachiyomi.ui.browse.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFavorite
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudFailure
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesException
import eu.kanade.tachiyomi.source.cloud.CloudPasswordLoginSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.BuiltInCloudConnectors
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.adult.AdultContentPolicy
import eu.kanade.tachiyomi.ui.adult.AdultContentSessionState
import eu.kanade.domain.adult.service.AdultContentPreferences
import eu.kanade.domain.adult.service.SourceContentRatingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CloudFavoritesViewModel(
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val adultContentSessionState: AdultContentSessionState,
    private val adultContentPreferences: AdultContentPreferences,
    private val sourceContentRatingStore: SourceContentRatingStore,
) : ViewModel() {

    val showAdultContent = adultContentSessionState.isShown
    fun setAdultContentShown(shown: Boolean) = adultContentSessionState.setShown(shown)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val providerJobs = mutableMapOf<CloudProviderKey, Job>()
    private val providerGenerations = mutableMapOf<CloudProviderKey, Long>()
    private var nextGeneration = 0L

    private val loadedCandidates = extensionManager.loadedExtensionsFlow.map { extensions ->
            extensions.flatMap { extension ->
                extension.sources.filterIsInstance<CloudFavoritesSource>().map { source ->
                    CloudConnectorCandidate(source, extension.pkgName)
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadedCandidates.collect { candidates -> refresh(candidates) }
        }
        viewModelScope.launch {
            combine(
                adultContentSessionState.isShown,
                adultContentPreferences.autoDetectionEnabled.changes(),
                ::Pair,
            ).collect { (showAdult, autoDetectionEnabled) ->
                if (_state.value.providers.isNotEmpty()) refilter(showAdult, autoDetectionEnabled)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refresh(loadedCandidates.first())
        }
    }

    private suspend fun refresh(candidates: List<CloudConnectorCandidate>) {
        providerJobs.values.forEach(Job::cancel)
        providerJobs.clear()
        val allCandidates = BuiltInCloudConnectors.sources.map {
            CloudConnectorCandidate(it, "内置", builtIn = true)
        } + candidates
        val resolved = CloudProviderKey.entries.associateWith { provider -> CloudConnectorRegistry.resolve(allCandidates, provider) }
        _state.update { old ->
            State(CloudProviderKey.entries.map { provider ->
                val previous = old.providers.firstOrNull { it.provider == provider }
                when (val candidate = resolved.getValue(provider)) {
                    CloudConnectorState.Missing -> ProviderState(provider, Status.Missing)
                    is CloudConnectorState.Duplicate -> ProviderState(provider, Status.Duplicate(candidate.packageNames))
                    is CloudConnectorState.Available -> ProviderState(provider, Status.Loading, candidate.source, previous?.items.orEmpty(), previous?.allItems.orEmpty(), previous?.nextPage ?: 2, previous?.hasNextPage ?: false)
                }
            })
        }
        resolved.forEach { (provider, candidate) ->
            (candidate as? CloudConnectorState.Available)?.let { startProviderLoad(it.source, provider) }
        }
    }

    private fun startProviderLoad(source: CloudFavoritesSource, provider: CloudProviderKey) {
        providerJobs[provider]?.cancel()
        val generation = ++nextGeneration
        providerGenerations[provider] = generation
        providerJobs[provider] = viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadProvider(
                source = source,
                provider = provider,
                showAdult = adultContentSessionState.isShown.value,
                autoDetectionEnabled = adultContentPreferences.autoDetectionEnabled.get(),
            )
            if (providerGenerations[provider] != generation) return@launch
            _state.update { state ->
                state.copy(providers = state.providers.map { current ->
                    if (current.provider != provider) current else mergeResult(current, loaded)
                })
            }
        }
    }

    private fun mergeResult(current: ProviderState, result: ProviderState): ProviderState = result.copy(
        items = if (result.status is Status.Failed && current.items.isNotEmpty()) current.items else result.items,
        allItems = if (result.status is Status.Failed && current.allItems.isNotEmpty()) current.allItems else result.allItems,
        nextPage = if (result.status is Status.Failed) current.nextPage else result.nextPage,
        hasNextPage = if (result.status is Status.Failed) current.hasNextPage else result.hasNextPage,
    )

    private fun refilter(showAdult: Boolean, autoDetectionEnabled: Boolean) {
        _state.update { state ->
            state.copy(providers = state.providers.map { provider ->
                if (provider.source == null || provider.allItems.isEmpty()) provider else provider.copy(
                    items = AdultContentPolicy.filter(provider.allItems, showAdult, autoDetectionEnabled, { sourceContentRatingStore.get(provider.source.targetSource.sourceId) }, { if (provider.provider == CloudProviderKey.COPY_MANGA) mihon.domain.extension.model.ContentWarning.SAFE else mihon.domain.extension.model.ContentWarning.NSFW }, { provider.source.targetSource.sourceId }, CloudFavorite::genres),
                )
            })
        }
    }

    private suspend fun loadProvider(
        source: CloudFavoritesSource,
        provider: CloudProviderKey,
        showAdult: Boolean,
        autoDetectionEnabled: Boolean,
    ): ProviderState = try {
        when (source.accountState()) {
            CloudAccountState.LoginRequired -> ProviderState(provider, Status.LoginRequired, source)
            CloudAccountState.SessionExpired -> ProviderState(provider, Status.SessionExpired, source)
            is CloudAccountState.Ready -> {
                val page = source.getCloudFavorites(1)
                val warning = if (provider == CloudProviderKey.COPY_MANGA) {
                    mihon.domain.extension.model.ContentWarning.SAFE
                } else {
                    mihon.domain.extension.model.ContentWarning.NSFW
                }
                val items = AdultContentPolicy.filter(
                    items = page.items,
                    showAdult = showAdult,
                    autoDetectionEnabled = autoDetectionEnabled,
                    overrideFor = { sourceContentRatingStore.get(source.targetSource.sourceId) },
                    warningFor = { warning },
                    sourceId = { source.targetSource.sourceId },
                    genres = CloudFavorite::genres,
                )
                ProviderState(provider, Status.Loaded, source, items, page.items, nextPage = page.page + 1, hasNextPage = page.hasNextPage)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudFavoritesException) {
        ProviderState(provider, Status.Failed(e.failure), source)
    } catch (e: Exception) {
        ProviderState(provider, Status.Failed(CloudFailure.Parsing), source)
    }

    fun loadMore(provider: CloudProviderKey) {
        var request: Pair<CloudFavoritesSource, Int>? = null
        while (request == null) {
            val currentState = _state.value
            val current = currentState.providers.firstOrNull { it.provider == provider } ?: return
            val source = current.source ?: return
            if (current.status != Status.Loaded || !current.hasNextPage || current.isLoading) return
            val nextState = currentState.copy(
                providers = currentState.providers.map {
                    if (it.provider == provider) it.copy(isLoading = true) else it
                },
            )
            if (_state.compareAndSet(currentState, nextState)) {
                request = source to current.nextPage
            }
        }
        val (source, pageNumber) = checkNotNull(request)
        providerJobs[provider]?.cancel()
        val generation = ++nextGeneration
        providerGenerations[provider] = generation
        providerJobs[provider] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = source.getCloudFavorites(pageNumber)
                if (providerGenerations[provider] != generation) return@launch
                val warning = if (provider == CloudProviderKey.COPY_MANGA) {
                    mihon.domain.extension.model.ContentWarning.SAFE
                } else {
                    mihon.domain.extension.model.ContentWarning.NSFW
                }
                _state.update { state ->
                    state.copy(providers = state.providers.map {
                        if (it.provider == provider) it.copy(
                            items = AdultContentPolicy.filter(
                                items = (it.allItems + page.items).distinctBy(CloudFavorite::remoteId),
                                showAdult = adultContentSessionState.isShown.value,
                                autoDetectionEnabled = adultContentPreferences.autoDetectionEnabled.get(),
                                overrideFor = { sourceContentRatingStore.get(source.targetSource.sourceId) },
                                warningFor = { warning },
                                sourceId = { source.targetSource.sourceId },
                                genres = CloudFavorite::genres,
                            ),
                            allItems = (it.allItems + page.items).distinctBy(CloudFavorite::remoteId),
                            nextPage = if (page.page == pageNumber) page.page + 1 else pageNumber,
                            hasNextPage = page.hasNextPage,
                            isLoading = false,
                        ) else it
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudFavoritesException) {
                _state.update { state ->
                    state.copy(providers = state.providers.map {
                        if (it.provider == provider) it.copy(
                            status = if (e.failure == CloudFailure.Authentication) {
                                Status.SessionExpired
                            } else {
                                Status.Failed(e.failure)
                            },
                            isLoading = false,
                        ) else it
                    })
                }
            } catch (e: Exception) {
                _state.update { state ->
                    state.copy(providers = state.providers.map {
                        if (it.provider == provider) it.copy(
                            status = Status.Failed(CloudFailure.Network),
                            isLoading = false,
                        ) else it
                    })
                }
            }
        }
    }

    fun retry(provider: CloudProviderKey) {
        val current = _state.value.providers.firstOrNull { it.provider == provider } ?: return
        val source = current.source ?: return
        _state.update { state -> state.copy(providers = state.providers.map { if (it.provider == provider) it.copy(status = Status.Loading) else it }) }
        startProviderLoad(source, provider)
    }

    fun login(source: CloudFavoritesSource, username: String, password: CharArray) {
        val loginSource = source as? CloudPasswordLoginSource ?: return
        _state.update { state -> state.copy(providers = state.providers.map { if (it.provider == source.providerKey) it.copy(status = Status.Loading) else it }) }
        providerJobs[source.providerKey]?.cancel()
        providerJobs[source.providerKey] = viewModelScope.launch(Dispatchers.IO) {
            try {
                loginSource.login(username, password)
                providerJobs.remove(source.providerKey)
                startProviderLoad(source, source.providerKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudFavoritesException) {
                _state.update { state -> state.copy(providers = state.providers.map { if (it.provider == source.providerKey) it.copy(status = Status.Failed(e.failure)) else it }) }
            } catch (_: Exception) {
                _state.update { state -> state.copy(providers = state.providers.map { if (it.provider == source.providerKey) it.copy(status = Status.Failed(CloudFailure.Network)) else it }) }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun open(favorite: CloudFavorite, source: CloudFavoritesSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = when (val resolution = CloudTargetResolver { sourceManager.get(it) }.resolve(source.targetSource)) {
                is CloudTargetResolution.Available -> resolution.source
                is CloudTargetResolution.Missing -> {
                    _events.trySend(Event.TargetMissing(resolution.target.displayName))
                    return@launch
                }
            }
            val manga = SManga.create().apply {
                url = favorite.canonicalUrl
                title = favorite.title
                thumbnail_url = favorite.thumbnailUrl
                author = favorite.author
                description = favorite.description
                genre = favorite.genres?.joinToString(", ")
            }
            val local = networkToLocalManga(manga.toDomainManga(target.id))
            _events.trySend(Event.Open(local.id))
        }.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                _events.trySend(Event.OpenFailed)
            }
        }
    }

    fun openSettings(source: CloudFavoritesSource) {
        _events.trySend(Event.Settings(source.id))
    }

    data class State(val providers: List<ProviderState> = emptyList())
    data class ProviderState(
        val provider: CloudProviderKey,
        val status: Status,
        val source: CloudFavoritesSource? = null,
        val items: List<CloudFavorite> = emptyList(),
        val allItems: List<CloudFavorite> = emptyList(),
        val nextPage: Int = 2,
        val hasNextPage: Boolean = false,
        val isLoading: Boolean = false,
    )

    sealed interface Status {
        data object Loading : Status
        data object Missing : Status
        data class Duplicate(val packageNames: List<String>) : Status
        data object LoginRequired : Status
        data object SessionExpired : Status
        data object Loaded : Status
        data class Failed(val failure: CloudFailure) : Status
    }

    sealed interface Event {
        data class Open(val mangaId: Long) : Event
        data class Settings(val sourceId: Long) : Event
        data class TargetMissing(val displayName: String) : Event
        data object OpenFailed : Event
    }
}
