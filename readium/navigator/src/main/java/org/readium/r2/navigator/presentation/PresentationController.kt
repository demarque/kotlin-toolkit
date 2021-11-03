package org.readium.r2.navigator.presentation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.readium.r2.navigator.ExperimentalPresentation
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.extensions.toStringPercentage
import org.readium.r2.shared.JSONable
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.presentation.Presentation.*
import org.readium.r2.shared.util.MapCompanion
import org.readium.r2.shared.util.getOrDefault
import java.lang.ref.WeakReference
import kotlin.math.round

/**
 * Helper class which simplifies the modification of Presentation Settings and designing a user
 * settings interface.
 *
 * @param autoActivateOnChange Requests the navigator to activate a non-active setting when its
 *        value is changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExperimentalPresentation
class PresentationController(
    settings: PresentationSettings? = null,
    private val coroutineScope: CoroutineScope,
    private val autoActivateOnChange: Boolean = true,
    private val listener: Listener? = null,
) {

    interface Listener {
        fun onSettingsChanged(settings: PresentationSettings): PresentationSettings = settings
    }

    private val _settings = MutableStateFlow(Settings(userSettings = settings ?: PresentationSettings()))
    val settings: StateFlow<Settings>
        get() = _settings.asStateFlow()

    private var _navigator: WeakReference<Navigator>? = null
    private val navigator: Navigator? get() = _navigator?.get()

    fun attach(navigator: Navigator) {
        _navigator = WeakReference(navigator)

        coroutineScope.launch {
            navigator.applySettings(settings.value.actualSettings)

            navigator.presentation.collect { presentation ->
                _settings.value = _settings.value.copy(presentation = presentation)
            }
        }
    }

    /**
     * Applies the current set of settings to the Navigator.
     */
    fun commit(changes: PresentationController.(Settings) -> Unit = {}) {
        changes(settings.value)
        coroutineScope.launch {
            navigator?.applySettings(settings.value.actualSettings)
        }
    }

    /**
     * Clears all user settings to revert to the Navigator default values.
     */
    fun reset() {
        set(PresentationSettings())
    }

    /**
     * Clears the given user setting to revert to the Navigator default value.
     */
    fun <T> reset(setting: Setting<T>?) {
        set(setting, null)
    }

    fun set(settings: PresentationSettings) {
        _settings.value = _settings.value.copy(
            userSettings = settings,
            actualSettings = listener?.onSettingsChanged(settings) ?: settings
        )
    }

    /**
     * Changes the value of the given setting.
     * The new value will be set in the user settings.
     */
    fun <T> set(setting: Setting<T>?, value: T?) {
        setting ?: return

        val settings = _settings.value

        var userSettings = settings.userSettings.copy {
            if (value == null) {
                remove(setting.key)
            } else {
                set(setting.key, setting.toJson(value))
            }
        }

        if (autoActivateOnChange) {
            settings.presentation.properties[setting.key]?.let { property ->
                userSettings = property.activateInSettings(userSettings)
                    .getOrDefault(userSettings)
            }
        }

        _settings.value = settings.copy(
            userSettings = userSettings,
            actualSettings = listener?.onSettingsChanged(userSettings) ?: userSettings
        )
    }

    /**
     * Inverts the value of the given toggle setting.
     */
    fun toggle(setting: ToggleSetting?) {
        setting ?: return

        set(setting, !(setting.value ?: setting.effectiveValue ?: false))
    }

    /**
     * Inverts the value of the given setting. If the setting is already set to the given value, it
     * is nulled out.
     */
    fun <T> toggle(setting: Setting<T>?, value: T) {
        setting ?: return

        if (setting.value == value) {
            reset(setting)
        } else {
            set(setting, value)
        }
    }

    /**
     * Increments the value of the given range setting to the next effective step.
     */
    fun increment(setting: RangeSetting?) {
        setting ?: return

        val step = setting.step
        val value = setting.value ?: setting.effectiveValue ?: 0.5

        set(setting, (value + step).roundToStep(setting.step).coerceAtMost(1.0))
    }

    /**
     * Decrements the value of the given range setting to the previous effective step.
     */
    fun decrement(setting: RangeSetting?) {
        setting ?: return

        val step = setting.step
        val value = setting.value ?: setting.effectiveValue ?: 0.5

        set(setting, (value - step).roundToStep(setting.step).coerceAtLeast(0.0))
    }

    private fun Double.roundToStep(step: Double): Double =
        round(this / step) * step

    data class Settings(
        val presentation: Presentation = Presentation(),
        val userSettings: PresentationSettings,
        val actualSettings: PresentationSettings = userSettings,
    ) : JSONable by userSettings {

        inline operator fun <reified T : Setting<*>> get(key: PresentationKey): T? {
            val property = presentation.properties[key]
            val userValue = userSettings.settings[key]
            val klass = T::class.java
            val isAvailable = property != null
            val isActive = property?.isActiveForSettings(actualSettings) ?: false
            return when {
                klass.isAssignableFrom(ToggleSetting::class.java) && property is Presentation.ToggleProperty? -> {
                    ToggleSetting(
                        key = key,
                        userValue = userValue as? Boolean,
                        effectiveValue = property?.value,
                        isAvailable = isAvailable,
                        isActive = isActive,
                    )
                }
                klass.isAssignableFrom(RangeSetting::class.java) && property is Presentation.RangeProperty? -> {
                    RangeSetting(
                        key = key,
                        userValue = userValue as? Double,
                        effectiveValue = property?.value,
                        stepCount = property?.stepCount,
                        isAvailable = isAvailable,
                        isActive = isActive,
                        labelForValue = { c, v -> property?.labelForValue(c, v) ?: v.toStringPercentage() }
                    )
                }
                klass.isAssignableFrom(StringSetting::class.java) && property is Presentation.StringProperty? -> {
                    StringSetting(
                        key = key,
                        userValue = userValue as? String,
                        effectiveValue = property?.value,
                        supportedValues = property?.supportedValues,
                        isAvailable = isAvailable,
                        isActive = isActive,
                        labelForValue = { c, v -> property?.labelForValue(c, v) ?: v }
                    )
                }
                else -> null
            } as? T
        }

        fun <T : Enum<T>> getEnum(key: PresentationKey, mapper: MapCompanion<String, T>): EnumSetting<T>? =
            get<StringSetting>(key)
                ?.let { EnumSetting(mapper, it) }

        val continuous: ToggleSetting?
            get() = get(PresentationKey.CONTINUOUS)

        val fit: EnumSetting<Fit>?
            get() = getEnum(PresentationKey.FIT, Fit)

        val orientation: EnumSetting<Orientation>?
            get() = getEnum(PresentationKey.ORIENTATION, Orientation)

        val overflow: EnumSetting<Overflow>?
            get() = getEnum(PresentationKey.OVERFLOW, Overflow)

        val pageSpacing: RangeSetting?
            get() = get(PresentationKey.PAGE_SPACING)

        val readingProgression: EnumSetting<ReadingProgression>?
            get() = getEnum(PresentationKey.READING_PROGRESSION, ReadingProgression)
    }

    /**
     * Holds the current value and the metadata of a Presentation Setting of type [T].
     *
     * @param key Presentation Key for this setting.
     * @param value Value taken from the current Presentation Settings.
     * @param effectiveValue Actual value in effect for the navigator.
     * @param isAvailable Indicates whether the Presentation Setting is available for the [navigator].
     * @param isActive Indicates whether the Presentation Setting is active for the current set of
     *        [userSettings].
     */
    sealed class Setting<T>(
        val key: PresentationKey,
        val value: T?,
        val effectiveValue: T?,
        val isAvailable: Boolean,
        val isActive: Boolean,
    ) {
        /**
         * Serializes the given value to its JSON type.
         */
        open fun toJson(value: T): Any = value as Any
    }

    class ToggleSetting(
        key: PresentationKey,
        userValue: Boolean?,
        effectiveValue: Boolean?,
        isAvailable: Boolean,
        isActive: Boolean,
    ) : Setting<Boolean>(key, userValue, effectiveValue, isAvailable = isAvailable, isActive = isActive)

    class RangeSetting(
        key: PresentationKey,
        userValue: Double?,
        effectiveValue: Double?,
        val stepCount: Int?,
        isAvailable: Boolean,
        isActive: Boolean,
        private val labelForValue: (Context, Double) -> String,
    ) : Setting<Double>(key, userValue, effectiveValue, isAvailable = isAvailable, isActive = isActive) {

        /**
         * Returns a user-facing localized label for the given value, which can be used in the user
         * interface.
         *
         * For example, with the "font size" property, the value 0.4 might have for label "12 pt",
         * depending on the Navigator.
         */
        fun labelForValue(context: Context, value: Double): String =
            labelForValue.invoke(context, value)

        internal val step: Double get() =
            if (stepCount == null || stepCount == 0) 0.1
            else 1.0 / stepCount

    }

    class StringSetting(
        key: PresentationKey,
        userValue: String?,
        effectiveValue: String?,
        val supportedValues: List<String>?,
        isAvailable: Boolean,
        isActive: Boolean,
        private val labelForValue: (Context, String) -> String,
    ) : Setting<String>(key, userValue, effectiveValue, isAvailable = isAvailable, isActive = isActive) {

        /**
         * Returns a user-facing localized label for the given value, which can be used in the user
         * interface.
         *
         * For example, with the "reading progression" property, the value ltr has for label "Left to
         * right" in English.
         */
        fun labelForValue(context: Context, value: String): String =
            labelForValue.invoke(context, value)
    }

    class EnumSetting<T : Enum<T>>(
        private val mapper: MapCompanion<String, T>,
        val stringSetting: StringSetting,
    ) : Setting<T?>(
        stringSetting.key,
        mapper.get(stringSetting.value),
        mapper.get(stringSetting.effectiveValue),
        isAvailable = stringSetting.isAvailable,
        isActive = stringSetting.isActive
    ) {

        constructor(
            mapper: MapCompanion<String, T>,
            key: PresentationKey,
            userValue: T?,
            effectiveValue: T?,
            supportedValues: List<T>?,
            isAvailable: Boolean,
            isActive: Boolean,
            labelForValue: (Context, T) -> String
        ) : this(mapper, StringSetting(
            key,
            userValue?.let { mapper.getKey(it) },
            effectiveValue?.let { mapper.getKey(it) },
            supportedValues?.map { mapper.getKey(it) },
            isAvailable = isAvailable,
            isActive = isActive,
            labelForValue = { c, v -> mapper.get(v)?.let { labelForValue(c, it) } ?: v }
        ))

        override fun toJson(value: T?): Any =
            value?.let { stringSetting.toJson(mapper.getKey(it)) } as Any

        val supportedValues: List<T>? = stringSetting.supportedValues
            ?.mapNotNull { mapper.get(it) }

        /**
         * Returns a user-facing localized label for the given value, which can be used in the user
         * interface.
         *
         * For example, with the "reading progression" property, the value ltr has for label "Left to
         * right" in English.
         */
        fun labelForValue(context: Context, value: T): String =
            stringSetting.labelForValue(context, mapper.getKey(value))
    }
}
