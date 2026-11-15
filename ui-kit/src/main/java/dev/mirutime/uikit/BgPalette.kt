package dev.mirutime.uikit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What a custom background image contributes to the theme. Null means "no
// usable swatch" — callers fall back to the Night palette, never crash.
// Only two things travel: the accent seed (its hue re-tints every role) and
// the per-band veil (how hard the picture has to be pressed where chrome sits).
data class BgPalette(
    val accent: Color,
    // Black veil alpha per horizontal band of the SCREEN (top→bottom), sized
    // so the brightest 10% of the band's pixels are pressed to gray ≤100/255:
    // light ink then holds ≥4.5:1 over the photo anywhere. Measured once per
    // decode, the rule replaces the old flat 0.30 veil.
    val veilBands: List<Float> = List(BgBands) { 0.30f },
)

// Separate 256px Coil request (software bitmap for Palette) + in-memory
// LruCache for ~12 backgrounds. The seed swatch is ungated: readability is
// enforced downstream by ensureContrastOn in the theme builder.
private const val PaletteSize = 256
private const val PaletteCacheSize = 12

const val BgBands = 8
// Light ink (white / 90% white) holds ≥4.5:1 over sRGB gray ≤ ~100.
private const val InkMaxLuma = 100f
private const val MinVeil = 0.28f
// The photo is drawn ContentScale.Crop on a portrait-locked phone: only the
// central slice of a wide (or tall) image is ever visible, so both crop axes
// are modelled and only that slice counts.
private const val ScreenAspect = 0.46f

private val paletteCache: LruCache<String, BgPalette?> = LruCache(PaletteCacheSize)
// Failed sources: LruCache can't tell a cached null from a miss, so misses
// get their own bounded cache instead of re-decoding on every process.
private val paletteMiss: LruCache<String, Boolean> = LruCache(PaletteCacheSize)

// Bumped when a source's file is replaced on disk. Composition keys carry it,
// so an already-composed background reloads instead of keeping the stale copy.
val bgPaletteRevision = mutableStateOf(0)

private fun pickSwatch(palette: Palette): Palette.Swatch? {
    // Vibrant family first by population: stylized art keeps its signature hue.
    // Fall back to dominant or muted if no vibrant swatch was extracted.
    val vibrant = listOfNotNull(
        palette.vibrantSwatch,
        palette.darkVibrantSwatch,
        palette.lightVibrantSwatch,
    )
    val muted = listOfNotNull(
        palette.mutedSwatch,
        palette.darkMutedSwatch,
        palette.lightMutedSwatch,
    )
    return vibrant.maxByOrNull { it.population }
        ?: palette.dominantSwatch
        ?: muted.maxByOrNull { it.population }
}

private class BandStats(val veil: FloatArray)

// 90th-percentile luma (perceptual weights) of each horizontal band of the
// VISIBLE crop, plus the black-veil alpha that presses it to InkMaxLuma.
// p90 rather than the mean: one bright sky must not hide behind a dark rest.
private fun measureBands(bitmap: Bitmap): BandStats {
    val w = bitmap.width
    val h = bitmap.height
    val veil = FloatArray(BgBands) { MinVeil }
    if (w <= 0 || h <= 0) return BandStats(veil)
    val aspect = w.toFloat() / h
    val visibleW = if (aspect > ScreenAspect) (w * ScreenAspect / aspect).toInt().coerceIn(1, w) else w
    val x0 = (w - visibleW) / 2
    val visibleH = if (aspect < ScreenAspect) (h * aspect / ScreenAspect).toInt().coerceIn(1, h) else h
    val yTop = (h - visibleH) / 2
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val hist = IntArray(256)
    for (band in 0 until BgBands) {
        java.util.Arrays.fill(hist, 0)
        val y0 = yTop + visibleH * band / BgBands
        val y1 = (yTop + visibleH * (band + 1) / BgBands).coerceIn(y0 + 1, yTop + visibleH)
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x0 + visibleW) {
                val c = pixels[y * w + x]
                val luma = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF))
                    .toInt().coerceIn(0, 255)
                hist[luma]++
                count++
                x += 2
            }
            y += 2
        }
        var acc = 0
        var level = 0
        val target = count * 0.9f
        while (level < 255 && acc + hist[level] < target) {
            acc += hist[level]
            level++
        }
        // Ceiling of the veil is 1 - InkMaxLuma/255 (0.608 at 255): brighter than
        // that never needs more.
        veil[band] = if (level <= InkMaxLuma) MinVeil else (1f - InkMaxLuma / level).coerceAtLeast(MinVeil)
    }
    // Bands bleed into each other under text that straddles a border: take
    // the stronger of own / 90% of neighbours.
    val smoothed = FloatArray(BgBands) { i ->
        maxOf(veil[i], veil.getOrElse(i - 1) { 0f } * 0.9f, veil.getOrElse(i + 1) { 0f } * 0.9f)
    }
    return BandStats(smoothed)
}

private suspend fun loadBgPalette(context: Context, source: String): BgPalette? {
    synchronized(paletteCache) {
        // LruCache returns null for a miss; recorded failures short-circuit
        // below without another decode.
        if (paletteCache[source] != null) return paletteCache[source]
    }
    synchronized(paletteMiss) {
        if (paletteMiss[source] != null) return null
    }
    val failed = { synchronized(paletteMiss) { paletteMiss.put(source, true) }; null }
    return try {
        val data: Any = when {
            source.startsWith("http", ignoreCase = true) -> source
            source.startsWith("content:") -> Uri.parse(source)
            else -> File(source)
        }
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(Size(PaletteSize, PaletteSize))
            .allowHardware(false)
            .build()
        val result = withContext(Dispatchers.IO) { loader.execute(request) }
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return failed()
        val (palette, bands) = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(16).generate() to measureBands(bitmap)
        }
        // Band measurement rides the same decode and ALWAYS succeeds for a
        // decodable picture: a bad swatch no longer drops the veil rule.
        // Seed: most populated vibrant swatch, else dominant/muted, else the
        // Night accent. Ungated — final readability is enforced downstream in
        // the theme builder (ensureContrastOn), not here.
        val swatch = pickSwatch(palette)
        val rgb = swatch?.rgb ?: 0xFFB2BCA8.toInt()
        val derived = BgPalette(
            accent = Color(rgb),
            veilBands = bands.veil.toList(),
        )
        synchronized(paletteCache) { paletteCache.put(source, derived) }
        derived
    } catch (_: Exception) {
        failed()
    }
}

// Drops the measured palette of a source whose file was replaced on disk and
// makes the already-composed readers reload it.
fun invalidateBgPaletteCache(source: String) {
    synchronized(paletteCache) { paletteCache.remove(source) }
    synchronized(paletteMiss) { paletteMiss.remove(source) }
    bgPaletteRevision.value++
}

@Composable
fun rememberBgPalette(source: String?): BgPalette? {
    val context = LocalContext.current.applicationContext
    val revision = bgPaletteRevision.value
    var palette by remember(source, revision) {
        mutableStateOf(source?.let { synchronized(paletteCache) { paletteCache[it] } })
    }
    LaunchedEffect(source, revision) {
        if (source == null) {
            palette = null
            return@LaunchedEffect
        }
        val cached = synchronized(paletteCache) { paletteCache[source] }
        if (cached != null) {
            palette = cached
            return@LaunchedEffect
        }
        palette = loadBgPalette(context, source)
    }
    return palette
}
