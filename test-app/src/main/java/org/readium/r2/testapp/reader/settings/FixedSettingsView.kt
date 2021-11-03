package org.readium.r2.testapp.reader.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.readium.r2.navigator.ExperimentalPresentation
import org.readium.r2.navigator.presentation.PresentationController
import org.readium.r2.navigator.presentation.PresentationKey
import org.readium.r2.navigator.presentation.PresentationSettings
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.presentation.Presentation
import org.readium.r2.shared.publication.presentation.Presentation.*
import org.readium.r2.testapp.utils.compose.ToggleButtonGroup

@OptIn(ExperimentalPresentation::class)
typealias UpdatePresentation = PresentationController.(PresentationController.Settings) -> Unit

@OptIn(ExperimentalPresentation::class)
typealias CommitPresentation = (UpdatePresentation) -> Unit

@Composable
@OptIn(ExperimentalPresentation::class)
fun FixedSettingsView(presentation: PresentationController) {
    val settings by presentation.settings.collectAsState()
    FixedSettingsView(
        settings = settings,
        commit = { presentation.commit(it) }
    )
}

@Composable
@OptIn(ExperimentalPresentation::class)
private fun FixedSettingsView(settings: PresentationController.Settings, commit: CommitPresentation) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "User settings",
            style = MaterialTheme.typography.subtitle1,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PresetsButton(
                commit,
                "Document" to { settings ->
                    set(settings.readingProgression, ReadingProgression.TTB)
                    set(settings.overflow, Overflow.SCROLLED)
                },
                "Ebook" to { settings ->
                    set(settings.readingProgression, ReadingProgression.LTR)
                    set(settings.overflow, Overflow.PAGINATED)
                },
                "Manga" to { settings ->
                    set(settings.readingProgression, ReadingProgression.RTL)
                    set(settings.overflow, Overflow.PAGINATED)
                },
            )
            Button(
                onClick = {
                    commit { reset() }
                },
            ) {
                Text("Reset")
            }
        }

        settings.readingProgression?.let { readingProgression ->
            val values = readingProgression.supportedValues ?: return@let

            Section("Reading progression", isActive = readingProgression.isActive) {
                ToggleButtonGroup(
                    options = values,
                    activeOption = readingProgression.effectiveValue,
                    selectedOption = readingProgression.value,
                    onSelectOption = { value ->
                        commit {
                            toggle(readingProgression, value)
                        }
                    }) { option ->
                    Icon(
                        imageVector = when (option) {
                            ReadingProgression.LTR -> Icons.Default.KeyboardArrowRight
                            ReadingProgression.RTL -> Icons.Default.KeyboardArrowLeft
                            ReadingProgression.TTB -> Icons.Default.KeyboardArrowDown
                            ReadingProgression.BTT -> Icons.Default.KeyboardArrowUp
                            ReadingProgression.AUTO -> Icons.Default.Clear
                        },
                        contentDescription = readingProgression.labelForValue(context, option)
                    )
                }
            }
        }

        EnumSection("Fit", settings.fit, commit)
        EnumSection("Overflow", settings.overflow, commit)
        EnumSection("Orientation", settings.orientation, commit)

        settings.pageSpacing?.let { pageSpacing ->
            Section("Page spacing", isActive = pageSpacing.isActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DecrementButton {
                        commit {
                            decrement(pageSpacing)
                        }
                    }
                    Text(pageSpacing.labelForValue(context, pageSpacing.value ?: pageSpacing.effectiveValue ?: 0.5))
                    IncrementButton {
                        commit {
                            increment(pageSpacing)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, isActive: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val alpha = if (isActive) 1.0f else ContentAlpha.disabled
    CompositionLocalProvider(LocalContentAlpha provides alpha) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.subtitle2,
            )
            content()
        }
    }
}

@Composable
@OptIn(ExperimentalPresentation::class)
private fun <T : Enum<T>> EnumSection(title: String, setting: PresentationController.EnumSetting<T>?, commit: CommitPresentation) {
    setting ?: return
    val values = setting.supportedValues ?: return

    Section(title) {
        ToggleButtonGroup(
            options = values,
            activeOption = setting.effectiveValue,
            selectedOption = setting.value,
            onSelectOption = { value ->
                commit {
                    toggle(setting, value)
                }
            }) { option ->
            Text(setting.labelForValue(LocalContext.current, option))
        }
    }
}

@Composable
fun DecrementButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Remove, contentDescription = "Less")
    }
}

@Composable
fun IncrementButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = "More")
    }
}

@Composable
@OptIn(ExperimentalPresentation::class)
fun PresetsButton(commit: CommitPresentation, vararg presets: Pair<String, UpdatePresentation>) {
    var isExpanded by remember { mutableStateOf(false) }
    fun dismiss() { isExpanded = false }

    Button(
        onClick = { isExpanded = true },
    ) {
        Text("Presets")
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            for ((title, changes) in presets) {
                DropdownMenuItem(
                    onClick = {
                        commit(changes)
                        dismiss()
                    }
                ) {
                    Text(title)
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
@OptIn(ExperimentalPresentation::class)
fun PreviewFixedSettingsView() {
    FixedSettingsView(
        settings = PresentationController.Settings(
            presentation = org.readium.r2.navigator.presentation.Presentation(
                PresentationKey.FIT to org.readium.r2.navigator.presentation.Presentation.StringProperty(
                    value = "",
                    supportedValues = listOf(Fit.CONTAIN, Fit.HEIGHT, Fit.WIDTH).map { it.value },
                ),
                PresentationKey.ORIENTATION to org.readium.r2.navigator.presentation.Presentation.StringProperty(
                    value = "",
                    supportedValues = listOf(
                        Orientation.PORTRAIT,
                        Orientation.LANDSCAPE
                    ).map { it.value },
                ),
                PresentationKey.OVERFLOW to org.readium.r2.navigator.presentation.Presentation.StringProperty(
                    value = "",
                    supportedValues = listOf(
                        Overflow.PAGINATED,
                        Overflow.SCROLLED
                    ).map { it.value },
                ),
                PresentationKey.PAGE_SPACING to org.readium.r2.navigator.presentation.Presentation.RangeProperty(
                    value = 0.6,
                    stepCount = 20,
                ),
                PresentationKey.READING_PROGRESSION to org.readium.r2.navigator.presentation.Presentation.StringProperty(
                    value = "",
                    supportedValues = listOf(
                        ReadingProgression.LTR, ReadingProgression.RTL,
                        ReadingProgression.TTB, ReadingProgression.BTT
                    ).map { it.value },
                ),
            ),
            userSettings = PresentationSettings(
                fit = Fit.WIDTH,
                orientation = Orientation.LANDSCAPE,
                overflow = Overflow.PAGINATED,
                pageSpacing = 0.3,
                readingProgression = ReadingProgression.TTB
            ),
        ),
        commit = {},
    )
}
