package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val themes = listOf(
    MR.strings.black_background to 1,
    MR.strings.gray_background to 2,
    MR.strings.white_background to 0,
    MR.strings.automatic_background to 3,
)

private val flashColors = listOf(
    MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
    MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
    MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
)

@Composable
internal fun ColumnScope.GeneralPage(viewModel: ReaderSettingsViewModel) {
    val readerTheme by viewModel.preferences.readerTheme.collectAsState()

    val flashPageState by viewModel.preferences.flashOnPageChange.collectAsState()

    val flashMillisPref = viewModel.preferences.flashDurationMillis
    val flashMillis by flashMillisPref.collectAsState()

    val flashIntervalPref = viewModel.preferences.flashPageInterval
    val flashInterval by flashIntervalPref.collectAsState()

    val flashColorPref = viewModel.preferences.flashColor
    val flashColor by flashColorPref.collectAsState()

    SettingsChipRow(MR.strings.pref_reader_theme) {
        themes.map { (labelRes, value) ->
            FilterChip(
                selected = readerTheme == value,
                onClick = { viewModel.preferences.readerTheme.set(value) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = viewModel.preferences.showPageNumber,
    )

    val verticalNavigatorModes by viewModel.preferences.verticalNavigator.collectAsState()

    SettingsChipRow(MR.strings.pref_vertical_navigator) {
        ReadingMode.entries.filter { it != ReadingMode.DEFAULT }.forEach { mode ->
            FilterChip(
                selected = verticalNavigatorModes.contains(mode),
                onClick = {
                    val newModes = if (verticalNavigatorModes.contains(mode)) {
                        verticalNavigatorModes - mode
                    } else {
                        verticalNavigatorModes + mode
                    }
                    viewModel.preferences.verticalNavigator.set(newModes)
                },
                label = { Text(stringResource(mode.stringRes)) },
            )
        }
    }

    if (verticalNavigatorModes.isNotEmpty()) {
        val verticalNavigatorHeightPref = viewModel.preferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        CheckboxItem(
            label = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
            pref = viewModel.preferences.verticalNavigatorOnLeft,
        )

        SliderItem(
            label = stringResource(MR.strings.pref_vertical_navigator_height),
            value = verticalNavigatorHeight,
            valueRange = 65..100,
            steps = 6,
            onChange = { verticalNavigatorHeightPref.set(it) },
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = viewModel.preferences.fullscreen,
    )

    val isFullscreen by viewModel.preferences.fullscreen.collectAsState()
    if (LocalActivity.current?.hasDisplayCutout() == true && isFullscreen) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = viewModel.preferences.drawUnderCutout,
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = viewModel.preferences.keepScreenOn,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = viewModel.preferences.readWithLongTap,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = viewModel.preferences.alwaysShowChapterTransition,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = viewModel.preferences.pageTransitions,
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = viewModel.preferences.flashOnPageChange,
    )
    if (flashPageState) {
        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            valueRange = 1..15,
            label = stringResource(MR.strings.pref_flash_duration),
            valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = flashInterval,
            valueRange = 1..10,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = {
                flashIntervalPref.set(it)
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            flashColors.map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }

    // region Manyue Enhancement
    Text(
        text = "Manyue 增强",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )

    val manyueMode by viewModel.preferences.manyueEnhancementMode.collectAsState()
    val readerActivity = LocalActivity.current as? ReaderActivity
    Text("增强模式", style = MaterialTheme.typography.bodyMedium)
    androidx.compose.foundation.layout.FlowRow {
        listOf(
            "原图 / OFF" to 0,
            "经典增强" to 1,
            "AI 超分" to 2,
            "AI 超分 + 经典增强" to 3,
        ).map { (label, value) ->
            FilterChip(
                selected = manyueMode == value,
                onClick = {
                    viewModel.preferences.manyueEnhancementMode.set(value)
                    readerActivity?.onManyueModeChanged(value)
                },
                label = { Text(label) },
            )
        }
    }

    val manyueStrength by viewModel.preferences.manyueClassicStrength.collectAsState()
    SliderItem(
        label = "经典增强强度",
        value = manyueStrength,
        valueRange = 0..100,
        steps = 99,
        onChange = {
            viewModel.preferences.manyueClassicStrength.set(it)
            readerActivity?.onManyueClassicStrengthChanged(it)
        },
    )

    val context = androidx.compose.ui.platform.LocalContext.current
    val aiCapability by produceState<eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiRuntime.Capability?>(
        initialValue = null,
        key1 = context,
    ) {
        value = withContext(Dispatchers.IO) {
            eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiRuntime.probe(context.applicationContext)
        }
    }
    Text(
        text = "AI 超分：${aiCapability?.userMessage ?: "正在校验运行环境"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = "启用增强时会使用支持 Manyue 的兼容阅读器；关闭增强后恢复高质量渲染器。",
        style = MaterialTheme.typography.bodySmall,
    )
    val aiDiagnostic by eu.kanade.tachiyomi.ui.reader.manyue.ManyueDiagnostics.latest.collectAsState()
    Text(
        text = "最近状态：$aiDiagnostic",
        style = MaterialTheme.typography.bodySmall,
    )

    val aiModelId by viewModel.preferences.manyueAiModel.collectAsState()
    Text("AI 模型", style = MaterialTheme.typography.bodyMedium)
    androidx.compose.foundation.layout.FlowRow {
        eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiModel.entries.map { model ->
            FilterChip(
                selected = aiModelId == model.id,
                onClick = {
                    viewModel.preferences.manyueAiModel.set(model.id)
                    readerActivity?.onManyueAiModelChanged(model.id)
                },
                label = { Text(model.label) },
            )
        }
    }
    Text(
        "快速模式使用 Real-CUGAN，质量模式使用 Real-ESRGAN；原图进行 2× 推理后按自定义倍率输出，保留源图细节。",
        style = MaterialTheme.typography.bodySmall,
    )
    val aiScalePercent by viewModel.preferences.manyueAiScalePercent.collectAsState()
    var draftScale by remember(aiScalePercent) { mutableIntStateOf(aiScalePercent.coerceIn(100, 200)) }
    Text("自定义超分倍率：%.2f×".format(draftScale / 100f), style = MaterialTheme.typography.bodyMedium)
    tachiyomi.presentation.core.components.material.Slider(
        value = draftScale,
        valueRange = 100..200,
        steps = 99,
        onValueChange = { draftScale = it },
        onValueChangeFinished = {
            // Commit only after release: dragging must not repeatedly rebuild the reader
            // or cancel/relaunch native inference for every one-percent step.
            if (draftScale != aiScalePercent) {
                viewModel.preferences.manyueAiScalePercent.set(draftScale)
                readerActivity?.onManyueAiScaleChanged(draftScale)
            }
        },
    )
    Text("1.00×保留原图；1.01–2.00×保持比例输出。超出内存安全预算时保留原图。", style = MaterialTheme.typography.bodySmall)
    val anime4kOverlay by viewModel.preferences.manyueAnime4kOverlay.collectAsState()
    androidx.compose.foundation.layout.FlowRow {
        FilterChip(
            selected = anime4kOverlay,
            onClick = {
                val enabled = !anime4kOverlay
                viewModel.preferences.manyueAnime4kOverlay.set(enabled)
                readerActivity?.onManyueAnime4kOverlayChanged(enabled)
            },
            label = { Text(if (anime4kOverlay) "Anime4KCPP CPU 叠加：开" else "Anime4KCPP CPU 叠加：关") },
        )
    }
    if (anime4kOverlay) {
        Text(
            "Anime4KCPP ACNet B4 在后台 CPU 后处理，可能增加出图等待；它不是 GPU shader。关闭时只使用所选 AI 模型。",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    val foldMode by viewModel.preferences.manyueFoldableMode.collectAsState()
    val foldManualWidth by viewModel.preferences.manyueFoldableTargetWidth.collectAsState()
    Text("折叠屏宽度", style = MaterialTheme.typography.bodyMedium)
    androidx.compose.foundation.layout.FlowRow {
        listOf("AUTO" to -1, "FULL" to 0, "MANUAL" to 1).map { (label, value) ->
            FilterChip(
                selected = if (value > 0) foldMode > 0 else foldMode == value,
                onClick = {
                    viewModel.preferences.manyueFoldableMode.set(value)
                    readerActivity?.onManyueFoldableWidthChanged(value, foldManualWidth)
                },
                label = { Text(label) },
            )
        }
    }
    if (foldMode > 0) {
        SliderItem(
            label = "手动宽度 / MANUAL WIDTH",
            value = foldManualWidth,
            valueRange = 1360..2880,
            steps = 94,
            valueString = "$foldManualWidth px",
            onChange = {
                viewModel.preferences.manyueFoldableTargetWidth.set(it)
                readerActivity?.onManyueFoldableWidthChanged(foldMode, it)
            },
        )
    }
    // endregion
}
