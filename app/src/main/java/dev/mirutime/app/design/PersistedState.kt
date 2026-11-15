package dev.mirutime.app.design

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// One mechanism for "the user picked it, so it stays picked": filters, sorts,
// shelf and tab selections survive leaving the screen and process restarts.
// Values are stored as strings in a small dedicated prefs file (not the
// backed-up settings snapshot), and a value that no longer decodes silently
// falls back to the default.
private const val ViewStateFile = "mirutime_view_state"
private const val SetSeparator = '\u001F'

object ViewStateStore {
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    fun init(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: context.applicationContext.getSharedPreferences(ViewStateFile, Context.MODE_PRIVATE).also {
                cachedPrefs = it
            }
        }
    }

    fun get(context: Context): SharedPreferences = init(context)

    // Same prefs instance the screens write through; null until init(), so a
    // caller without a Context is a no-op instead of a crash.
    fun remove(key: String) {
        cachedPrefs?.edit()?.remove(key)?.apply()
    }
}

// Per-book view state (chapter filter) is keyed by bookUrl; the data layer that
// removes a book has no Context, so it reuses the prefs MiruApp initialized at
// startup.
fun removePersistedState(key: String) {
    ViewStateStore.remove(key)
}

@Composable
fun <T> rememberPersistedState(
    key: String,
    default: T,
    encode: (T) -> String,
    decode: (String) -> T?,
): MutableState<T> {
    val context = LocalContext.current
    val prefs = remember(context) {
        ViewStateStore.get(context)
    }
    val state = remember(key) {
        mutableStateOf(
            prefs.getString(key, null)?.let { raw -> runCatching { decode(raw) }.getOrNull() } ?: default,
        )
    }
    // An absent key already decodes to the default, so persisting the default
    // would pin it and a future default change would never reach existing users.
    val value = state.value
    LaunchedEffect(key, value) {
        val encoded = encode(value)
        if (value == default && !prefs.contains(key)) return@LaunchedEffect
        if (prefs.getString(key, null) != encoded) {
            prefs.edit().putString(key, encoded).apply()
        }
    }
    return state
}

@Composable
fun rememberPersistedStringSet(key: String): MutableState<Set<String>> =
    rememberPersistedState(
        key = key,
        default = emptySet(),
        encode = { it.sorted().joinToString(SetSeparator.toString()) },
        decode = { raw ->
            if (raw.isEmpty()) emptySet() else raw.split(SetSeparator).toSet()
        },
    )

@Composable
fun rememberPersistedString(key: String, default: String): MutableState<String> =
    rememberPersistedState(key = key, default = default, encode = { it }, decode = { it })

@Composable
fun rememberPersistedBoolean(key: String, default: Boolean): MutableState<Boolean> =
    rememberPersistedState(
        key = key,
        default = default,
        encode = { it.toString() },
        decode = { it.toBooleanStrictOrNull() },
    )

// Nullable enum: null is stored as the empty string.
@Composable
inline fun <reified E : Enum<E>> rememberPersistedEnum(key: String, default: E): MutableState<E> =
    rememberPersistedState(
        key = key,
        default = default,
        encode = { it.name },
        decode = { raw -> enumValues<E>().firstOrNull { it.name == raw } },
    )

@Composable
inline fun <reified E : Enum<E>> rememberPersistedOptionalEnum(key: String): MutableState<E?> =
    rememberPersistedState<E?>(
        key = key,
        default = null,
        encode = { it?.name.orEmpty() },
        decode = { raw -> if (raw.isEmpty()) null else enumValues<E>().firstOrNull { it.name == raw } },
    )
