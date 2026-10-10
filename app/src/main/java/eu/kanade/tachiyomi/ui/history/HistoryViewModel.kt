package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.adult.service.AdultContentPreferences
import eu.kanade.domain.adult.service.SourceContentRatingStore
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.ui.adult.AdultContentPolicy
import eu.kanade.tachiyomi.ui.adult.AdultContentSessionState
import eu.kanade.tachiyomi.ui.adult.SourceContentWarningResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import mihon.domain.extension.model.ContentWarning
import mihon.domain.content.model.ContentRatingOverride
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(
    private val addTracks: AddTracks,
    private val getCategories: GetCategories,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getHistory: GetHistory,
    private val getManga: GetManga,
    private val getNextChapters: GetNextChapters,
    private val libraryPreferences: LibraryPreferences,
    private val removeHistory: RemoveHistory,
    private val setMangaCategories: SetMangaCategories,
    private val updateManga: UpdateManga,
    private val sourceManager: SourceManager,
    private val adultContentSessionState: AdultContentSessionState,
    private val adultContentPreferences: AdultContentPreferences,
    private val sourceContentWarningResolver: SourceContentWarningResolver,
    private val sourceContentRatingStore: SourceContentRatingStore,
) : ViewModel() {

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val history = combine(
        searchQuery.flatMapLatest { query ->
            getHistory.subscribe(query ?: "")
                .distinctUntilChanged()
                .catch { error ->
                    logcat(LogPriority.ERROR, error)
                    _events.send(Event.InternalError)
                }
        },
        adultContentPreferences.autoDetectionEnabled.changes(),
        sourceContentRatingStore.changes(),
        sourceContentWarningResolver.warnings,
    ) { history, autoDetectionEnabled, sourceOverrides, warningMap ->
        HistoryInputs(history, autoDetectionEnabled, sourceOverrides, warningMap)
    }
        .mapLatest { inputs ->
            val records = inputs.history.map { item ->
                HistoryRecord(item, getManga.await(item.mangaId))
            }
            val partition = AdultContentPolicy.partition(
                items = records,
                autoDetectionEnabled = inputs.autoDetectionEnabled,
                overrideFor = { record ->
                    record.manga?.contentRatingOverride?.takeIf {
                        it != ContentRatingOverride.AUTO
                    } ?: record.manga?.let { sourceContentRatingStore.get(it.source, inputs.sourceOverrides) }
                        ?: ContentRatingOverride.ADULT
                },
                warningFor = { sourceId -> inputs.warningMap[sourceId] ?: ContentWarning.SAFE },
                sourceId = { it.manga?.source ?: 0L },
                genres = { it.manga?.genre },
            )
            HistoryContent(
                regularList = partition.regular
                    .map { it.history }
                    .toHistoryUiModels(HistoryUiModel.Section.REGULAR),
                adultList = partition.adult
                    .map { it.history }
                    .toHistoryUiModels(HistoryUiModel.Section.ADULT),
                adultMangaIds = partition.adult.mapTo(mutableSetOf()) { it.history.mangaId },
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), HistoryContent())

    private data class HistoryInputs(
        val history: List<HistoryWithRelations>,
        val autoDetectionEnabled: Boolean,
        val sourceOverrides: Set<String>,
        val warningMap: Map<Long, ContentWarning>,
    )

    private data class HistoryRecord(
        val history: HistoryWithRelations,
        val manga: Manga?,
    )

    private data class HistoryContent(
        val regularList: List<HistoryUiModel>? = null,
        val adultList: List<HistoryUiModel> = emptyList(),
        val adultMangaIds: Set<Long> = emptySet(),
    )

    init {
        adultContentSessionState.isShown
            .onEach { isShown ->
                if (!isShown) {
                    val adultMangaIds = history.value.adultMangaIds
                    dialog.update { current ->
                        current?.takeUnless { it.referencesAdultContent(adultMangaIds) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    val state: StateFlow<State> = combine(
        searchQuery,
        history,
        dialog,
        adultContentSessionState.isShown,
    ) { searchQuery, history, dialog, showAdult ->
        State(
            searchQuery = searchQuery,
            list = history.regularList,
            adultList = if (showAdult) history.adultList else emptyList(),
            hasAdultHistory = history.adultList.isNotEmpty(),
            showAdult = showAdult,
            dialog = if (showAdult) dialog else dialog?.takeUnless {
                it.referencesAdultContent(history.adultMangaIds)
            },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private fun List<HistoryWithRelations>.toHistoryUiModels(
        section: HistoryUiModel.Section,
    ): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate, section)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext {
            val latestVisibleHistory = getHistory.subscribe("")
                .first()
                .distinctBy { it.mangaId }
                .firstOrNull { isMangaVisibleNow(it.mangaId) }
                ?: return@withIOContext null
            getNextChapters.await(
                mangaId = latestVisibleHistory.mangaId,
                fromChapterId = latestVisibleHistory.chapterId,
                onlyUnread = false,
            ).firstOrNull()
        }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        viewModelScope.launchIO {
            if (!isMangaVisibleNow(mangaId)) return@launchIO
            val chapter = getNextChapters.await(mangaId, chapterId, onlyUnread = false).firstOrNull()
            if (chapter == null || isChapterVisibleNow(chapter)) {
                _events.send(Event.OpenChapter(chapter))
            }
        }
    }

    suspend fun isChapterVisibleNow(chapter: Chapter): Boolean = isMangaVisibleNow(chapter.mangaId)

    private suspend fun isMangaVisibleNow(mangaId: Long): Boolean {
        val manga = getManga.await(mangaId) ?: return false
        return AdultContentPolicy.shouldShow(
            showAdult = adultContentSessionState.isShown.value,
            autoDetectionEnabled = adultContentPreferences.autoDetectionEnabled.get(),
            override = manga.contentRatingOverride.takeIf {
                it != ContentRatingOverride.AUTO
            } ?: sourceContentRatingStore.get(manga.source),
            detectedAdult = AdultContentPolicy.isAdult(
                sourceId = manga.source,
                warning = sourceContentWarningResolver.warningFor(manga.source),
                genres = manga.genre,
            ),
        )
    }

    fun toggleAdultContent() {
        adultContentSessionState.toggle()
    }

    private fun Dialog.referencesAdultContent(adultMangaIds: Set<Long>): Boolean = when (this) {
        is Dialog.Delete -> history.mangaId in adultMangaIds
        is Dialog.DuplicateManga -> manga.id in adultMangaIds
        is Dialog.ChangeCategory -> manga.id in adultMangaIds
        is Dialog.Migrate -> target.id in adultMangaIds || current.id in adultMangaIds
        Dialog.DeleteAll -> false
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        viewModelScope.launchIO {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        viewModelScope.launchIO {
            removeHistory.await(mangaId)
        }
    }

    fun removeAllHistory() {
        viewModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private fun moveMangaToCategory(mangaId: Long, categories: Category?) {
        val categoryIds = listOfNotNull(categories).map { it.id }
        moveMangaToCategory(mangaId, categoryIds)
    }

    private fun moveMangaToCategory(mangaId: Long, categoryIds: List<Long>) {
        viewModelScope.launchIO {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(manga.id, categories)
        if (manga.favorite) return

        viewModelScope.launchIO {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun addFavorite(mangaId: Long) {
        viewModelScope.launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO

            val duplicates = getDuplicateLibraryManga(manga)
            if (duplicates.isNotEmpty()) {
                dialog.update { Dialog.DuplicateManga(manga, duplicates) }
                return@launchIO
            }

            addFavorite(manga)
        }
    }

    fun addFavorite(manga: Manga) {
        viewModelScope.launchIO {
            // Move to default category if applicable
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                // Default category set
                defaultCategory != null -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, defaultCategory)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0L || categories.isEmpty() -> {
                    val result = updateManga.awaitUpdateFavorite(manga.id, true)
                    if (!result) return@launchIO
                    moveMangaToCategory(manga.id, null)
                }

                // Choose a category
                else -> showChangeCategoryDialog(manga)
            }

            // Sync with tracking services if applicable
            addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source))
        }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        dialog.update { Dialog.Migrate(target = target, current = current) }
    }

    fun showChangeCategoryDialog(manga: Manga) {
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.update {
                Dialog.ChangeCategory(
                    manga = manga,
                    initialSelection = categories.mapAsCheckboxState { it.id in selection },
                )
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val adultList: List<HistoryUiModel> = emptyList(),
        val hasAdultHistory: Boolean = false,
        val showAdult: Boolean = false,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
