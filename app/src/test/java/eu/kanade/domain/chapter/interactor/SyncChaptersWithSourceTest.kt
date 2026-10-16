package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

class SyncChaptersWithSourceTest {

    @Test
    fun newChaptersKeepUnknownAndExplicitUploadDates() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        coEvery { chapterRepository.getChapterByMangaId(any(), any()) } returns emptyList()
        coEvery { chapterRepository.addAll(any()) } coAnswers { firstArg() }
        val sync = createSync(chapterRepository)
        val explicitUploadDate = 1_700_000_000_000L

        val result = sync.await(
            rawSourceChapters = listOf(
                sourceChapter("unknown", dateUpload = 0L),
                sourceChapter("dated", dateUpload = explicitUploadDate),
            ),
            manga = testManga(),
            source = testSource(),
        )

        assertEquals(listOf(0L, explicitUploadDate), result.map { it.dateUpload })
        assertTrue(result.all { it.dateFetch > 0L })
    }

    @Test
    fun sourceUnknownDoesNotEraseAnExistingUploadDate() = runTest {
        val storedUploadDate = 1_700_000_000_000L
        val existingChapter = Chapter.create().copy(
            id = 1L,
            mangaId = MANGA_ID,
            url = "known-date",
            name = "Chapter 1",
            dateUpload = storedUploadDate,
            chapterNumber = 1.0,
        )
        val chapterRepository = mockk<ChapterRepository>()
        coEvery { chapterRepository.getChapterByMangaId(any(), any()) } returns listOf(existingChapter)
        val updateChapter = mockk<UpdateChapter>(relaxed = true)
        val updateSlot = slot<List<ChapterUpdate>>()
        val sync = createSync(chapterRepository, updateChapter)

        sync.await(
            rawSourceChapters = listOf(sourceChapter("known-date", dateUpload = 0L)),
            manga = testManga(),
            source = testSource(),
        )

        coVerify(exactly = 1) { updateChapter.awaitAll(capture(updateSlot)) }
        assertEquals(storedUploadDate, updateSlot.captured.single().dateUpload)
    }

    private fun createSync(
        chapterRepository: ChapterRepository,
        updateChapter: UpdateChapter = mockk(relaxed = true),
    ): SyncChaptersWithSource {
        val getExcludedScanlators = mockk<GetExcludedScanlators>()
        coEvery { getExcludedScanlators.await(any()) } returns emptySet()

        val libraryPreferences = mockk<LibraryPreferences>(relaxed = true)
        every { libraryPreferences.markDuplicateReadChapterAsRead.get() } returns emptySet()

        return SyncChaptersWithSource(
            downloadManager = mockk<DownloadManager>(relaxed = true),
            downloadProvider = mockk<DownloadProvider>(relaxed = true),
            chapterRepository = chapterRepository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = mockk<UpdateManga>(relaxed = true),
            updateChapter = updateChapter,
            getChaptersByMangaId = GetChaptersByMangaId(chapterRepository),
            getExcludedScanlators = getExcludedScanlators,
            libraryPreferences = libraryPreferences,
        )
    }

    private fun sourceChapter(url: String, dateUpload: Long): SChapter = SChapter.create().apply {
        this.url = url
        name = "Chapter 1"
        chapter_number = 1f
        date_upload = dateUpload
    }

    private fun testManga() = Manga.create().copy(
        id = MANGA_ID,
        source = SOURCE_ID,
        title = "Test manga",
    )

    private fun testSource() = mockk<Source> {
        every { id } returns SOURCE_ID
    }

    private companion object {
        const val MANGA_ID = 42L
        const val SOURCE_ID = 123L
    }
}
