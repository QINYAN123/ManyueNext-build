package eu.kanade.presentation.reader.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceDiagnostics
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import eu.kanade.tachiyomi.util.system.toShareIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val aiModelId by viewModel.preferences.manyueAiModel.collectAsState()
    val selectedAiModel = eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiModel.fromId(aiModelId)
    val readerActivity = LocalActivity.current as? ReaderActivity
    val gpuEnabled by viewModel.preferences.manyueGpuDisplayFilter.collectAsState()
    val gpuStrength by viewModel.preferences.manyueGpuDisplayStrength.collectAsState()
    val gpuSupported = android.os.Build.VERSION.SDK_INT >= 33
    var draftGpuStrength by remember(gpuStrength) { mutableIntStateOf(gpuStrength.coerceIn(0, 100)) }
    DisposableEffect(readerActivity) {
        onDispose {
            // If a drag is cancelled by dismissing settings, discard its unsaved preview.
            readerActivity?.onManyueGpuDisplayChanged(
                viewModel.preferences.manyueGpuDisplayFilter.get(),
                viewModel.preferences.manyueGpuDisplayStrength.get(),
            )
        }
    }
    Text("GPU 显示增强（试验）", style = MaterialTheme.typography.bodyMedium)
    FilterChip(
        selected = gpuEnabled,
        enabled = gpuSupported || gpuEnabled,
        onClick = {
            val enabled = !gpuEnabled
            viewModel.preferences.manyueGpuDisplayFilter.set(enabled)
            readerActivity?.onManyueGpuDisplayChanged(enabled, gpuStrength)
        },
        label = { Text(if (gpuEnabled) "显示滤镜：开" else "显示滤镜：关") },
    )
    if (gpuEnabled && gpuSupported) {
        Text("滤镜强度：$draftGpuStrength%", style = MaterialTheme.typography.bodyMedium)
        tachiyomi.presentation.core.components.material.Slider(
            value = draftGpuStrength,
            valueRange = 0..100,
            steps = 99,
            onValueChange = {
                draftGpuStrength = it
                readerActivity?.onManyueGpuDisplayChanged(true, it)
            },
            onValueChangeFinished = {
                viewModel.preferences.manyueGpuDisplayStrength.set(draftGpuStrength)
            },
        )
    }
    Text(
        if (gpuSupported) {
            "随显示增强边缘和轻微明暗层次，原图文件与分辨率保留。先选下方“原图 / OFF”单独试滤镜，建议强度 25%。设为 0% 可对照同一阅读器的原显示。滤镜不重建缺失细节；同时开启下方图片增强会叠加效果与开销。"
        } else {
            "GPU 显示滤镜需要 Android 13 或以上；当前系统保留原有显示。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (readerActivity != null) {
        val gpuStatus by readerActivity.manyueGpuDisplayStatus.collectAsState()
        gpuStatus.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    Text("增强模式", style = MaterialTheme.typography.bodyMedium)
    androidx.compose.foundation.layout.FlowRow {
        listOf(
            "原图 / OFF" to 0,
            "原尺寸细节增强" to 1,
            "AI 超分" to 2,
            "AI 超分 + 经典细节增强" to 3,
            "智能增强" to 4,
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
    if (eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.fromInt(manyueMode).usesClassic(selectedAiModel)) {
        var draftStrength by remember(manyueStrength) { mutableIntStateOf(manyueStrength.coerceIn(0, 100)) }
        Text("经典细节增强（CPU）强度：$draftStrength", style = MaterialTheme.typography.bodyMedium)
        tachiyomi.presentation.core.components.material.Slider(
            value = draftStrength,
            valueRange = 0..100,
            steps = 99,
            onValueChange = { draftStrength = it },
            onValueChangeFinished = {
                if (draftStrength != manyueStrength) {
                    viewModel.preferences.manyueClassicStrength.set(draftStrength)
                    readerActivity?.onManyueClassicStrengthChanged(draftStrength)
                }
            },
        )
    }
    if (manyueMode == 4) {
        Text("按阅读区实际宽度判断：不够宽时 AI 超分；已经足够时原尺寸增强。压缩严重的图可手动选择 AI 超分。", style = MaterialTheme.typography.bodySmall)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val aiCapability by produceState<eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiRuntime.Capability?>(
        initialValue = null,
        key1 = context,
        key2 = selectedAiModel,
    ) {
        value = withContext(Dispatchers.IO) {
            eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiRuntime.probe(context.applicationContext, selectedAiModel)
        }
    }
    Text(
        text = "AI 超分：${aiCapability?.userMessage ?: "正在校验运行环境"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = "启用图片增强或 GPU 滤镜时使用兼容阅读器；两者都关闭后恢复首选渲染器。",
        style = MaterialTheme.typography.bodySmall,
    )
    val aiDiagnostic by eu.kanade.tachiyomi.ui.reader.manyue.ManyueDiagnostics.latest.collectAsState()
    Text(
        text = "最近状态：$aiDiagnostic",
        style = MaterialTheme.typography.bodySmall,
    )

    val diagnosticsEnabled by viewModel.preferences.manyuePerformanceDiagnostics.collectAsState()
    val diagnosticsScope = rememberCoroutineScope()
    var reportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    FilterChip(
        selected = diagnosticsEnabled,
        onClick = {
            val enabled = !diagnosticsEnabled
            viewModel.preferences.manyuePerformanceDiagnostics.set(enabled)
            readerActivity?.onManyuePerformanceDiagnosticsChanged(enabled)
            reportMessage = null
        },
        label = { Text(if (diagnosticsEnabled) "性能诊断：开" else "性能诊断：关") },
    )
    Text("排队、推理、显示准备、替换与帧耗时。只保留最近记录，不含图片、源地址或账号。诊断不改变增强设置。", style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.Row {
        TextButton(onClick = {
            readerActivity?.resetManyuePerformanceDiagnostics()
            reportMessage = "已清空诊断记录"
        }, enabled = !exporting) { Text("清空记录") }
        TextButton(onClick = {
            exporting = true
            diagnosticsScope.launch {
                try {
                    val file =
                        withContext(Dispatchers.IO) { ManyuePerformanceDiagnostics.export(context.applicationContext) }
                    reportMessage = "已保存 ${file.name}，可在分享面板保存报告"
                    context.startActivity(file.getUriCompat(context).toShareIntent(context, "application/json"))
                } catch (_: Exception) {
                    reportMessage = "报告导出失败，请重试"
                } finally {
                    exporting = false
                }
            }
        }, enabled = !exporting) { Text(if (exporting) "正在导出" else "导出性能报告") }
    }
    reportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

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
        if (selectedAiModel.continuousScale) {
            "轻量模型同时支持原尺寸修复和连续倍率超分，直接生成所选尺寸。优先使用 GPU；实际后端记录在每页完成状态中。"
        } else {
            "Real-CUGAN 与 Real-ESRGAN 仍运行完整 2×模型，再按所选倍率输出；降低倍率不会减少核心推理量。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    val aiScalePercent by viewModel.preferences.manyueAiScalePercent.collectAsState()
    var draftScale by remember(aiScalePercent) { mutableIntStateOf(aiScalePercent.coerceIn(100, 200)) }
    Text(
        (
            if (manyueMode ==
                4
            ) {
                "智能模式最高倍率：%.2f×"
            } else {
                "自定义超分倍率：%.2f×"
            }
            ).format(draftScale / 100f),
        style = MaterialTheme.typography.bodyMedium,
    )
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
    Text(
        if (selectedAiModel.continuousScale) {
            if (manyueMode == 4) {
                "不够宽时按阅读区宽度与倍率上限超分；已经足够宽时进行 1×轻量 AI 修复。原图文件保留。"
            } else {
                "1×做原尺寸 AI 修复；1.01–2×直接按目标尺寸重建。倍率越低，输出重建计算越少；原图文件保留。"
            }
        } else if (manyueMode == 4) {
            "智能模式输出不超过阅读区宽度与所选上限。需要 AI 时仍运行 2×模型；原图文件保留。"
        } else {
            "1×仍执行 2×模型再缩回原尺寸；1.01–2×保持比例输出。原图文件保留。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (selectedAiModel.continuousScale) {
        val aiDetailStrength by viewModel.preferences.manyueAiDetailStrength.collectAsState()
        var draftDetail by remember(aiDetailStrength) { mutableIntStateOf(aiDetailStrength.coerceIn(0, 100)) }
        Text("AI 修复强度：$draftDetail", style = MaterialTheme.typography.bodyMedium)
        tachiyomi.presentation.core.components.material.Slider(
            value = draftDetail,
            valueRange = 0..100,
            steps = 99,
            onValueChange = { draftDetail = it },
            onValueChangeFinished = {
                if (draftDetail != aiDetailStrength) {
                    viewModel.preferences.manyueAiDetailStrength.set(draftDetail)
                    readerActivity?.onManyueAiDetailStrengthChanged(draftDetail)
                }
            },
        )
        Text("控制模型修复的力度，与输出倍率独立；0 为普通插值，60 为温和修复。建议先关闭额外叠加增强，避免过锐。", style = MaterialTheme.typography.bodySmall)
    }
    val anime4kOverlay by viewModel.preferences.manyueAnime4kOverlay.collectAsState()
    androidx.compose.foundation.layout.FlowRow {
        FilterChip(
            selected = anime4kOverlay && !selectedAiModel.continuousScale,
            enabled = !selectedAiModel.continuousScale,
            onClick = {
                val enabled = !anime4kOverlay
                viewModel.preferences.manyueAnime4kOverlay.set(enabled)
                readerActivity?.onManyueAnime4kOverlayChanged(enabled)
            },
            label = { Text(if (anime4kOverlay) "Anime4KCPP CPU 叠加：开" else "Anime4KCPP CPU 叠加：关") },
        )
    }
    if (selectedAiModel.continuousScale) {
        Text("轻量模型使用自身的 AI 修复强度；Anime4KCPP CPU 叠加仅用于旧模型。", style = MaterialTheme.typography.bodySmall)
    } else if (anime4kOverlay) {
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
