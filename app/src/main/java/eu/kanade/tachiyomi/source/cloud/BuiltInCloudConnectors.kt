package eu.kanade.tachiyomi.source.cloud

import app.manyuenext.cloud.copymanga.CopyMangaCloudSource
import app.manyuenext.cloud.jm.JmCloudSource
import app.manyuenext.cloud.picacg.PicacgCloudSource

/**
 * Cloud connectors shipped with ManyueNext itself.
 *
 * The instances keep the same source IDs as the separately installable
 * compatibility APKs, so encrypted sessions and source preferences survive a
 * migration. External connector APKs are still tolerated as a fallback, but
 * the host always prefers these built-in instances.
 */
object BuiltInCloudConnectors {
    val sources: List<CloudFavoritesSource> by lazy {
        buildList<CloudFavoritesSource> {
            add(CopyMangaCloudSource())
            add(JmCloudSource())
            add(PicacgCloudSource())
        }
    }
}
