package dev.rain.laundry.ui

import org.junit.Assert.*
import org.junit.Test

class SplashImageSettingsTest {
    private fun image(index: Int) = "splash_${index.toString(16).padStart(32, '0')}.jpg"

    @Test fun missingOrCorruptPreferencesUseBothDefaults() {
        assertEquals(defaultSplashImages, effectiveSplashImages(decodeSplashImages(null)))
        for (saved in listOf("broken", "../../photo.jpg", "content://gallery/1", "x".repeat(1025))) {
            assertEquals(defaultSplashImages, effectiveSplashImages(decodeSplashImages(saved)))
        }
    }

    @Test fun customOrderSurvivesSerializationAndRemovesDuplicates() {
        val chosen = listOf(image(3), image(1), image(2))
        assertEquals(chosen, decodeSplashImages(encodeSplashImages(chosen + image(1))))
        assertEquals(chosen, effectiveSplashImages(chosen))
    }

    @Test fun invalidNamesCannotEscapePrivateDirectoryAndDoNotHideValidChoices() {
        val names = listOf("../${image(1)}", "/${image(2)}", "default:splash_mascot_1", image(3), "splash_abc.jpg")
        assertEquals(listOf(image(3)), normalizeSplashImageNames(names))
    }

    @Test fun preferenceListHasTheSameTwelveImageBoundAsImport() {
        assertEquals((1..12).map(::image), normalizeSplashImageNames((1..20).map(::image)))
    }

    @Test fun missingOrInvalidDurationFallsBackToSevenHundredMilliseconds() {
        for (saved in listOf(null, Int.MIN_VALUE, -1, 0, 99, 60_001, Int.MAX_VALUE)) {
            assertEquals(DEFAULT_SPLASH_DURATION_MS, savedSplashDurationMs(saved))
        }
        for (saved in listOf(100, 700, 1500, 60_000)) assertEquals(saved, savedSplashDurationMs(saved))
        assertEquals(100, normalizeSplashDurationMs(-1))
        assertEquals(60_000, normalizeSplashDurationMs(Int.MAX_VALUE))
    }

    @Test fun eachColdStartTakesOneImageAndTheSequenceWrapsInOrder() {
        val images = effectiveSplashImages(listOf(image(3), image(1), image(2)))
        var savedIndex: Int? = null
        val shown = (1..7).map {
            val (current, next) = splashSequenceStep(savedIndex, images.size)
            savedIndex = next
            images[current]
        }
        assertEquals(listOf(image(3), image(1), image(2), image(3), image(1), image(2), image(3)), shown)
    }

    @Test fun singleImageRepeatsAndCorruptOrOldSequencePositionsRestartAtTheFirstImage() {
        assertEquals(0 to 0, splashSequenceStep(0, 1))
        for (saved in listOf(null, -1, Int.MIN_VALUE, 2, Int.MAX_VALUE)) {
            assertEquals(0 to 1, splashSequenceStep(saved, 2))
        }
        val defaultSequence = effectiveSplashImages(emptyList())
        assertEquals(0 to 1, splashSequenceStep(null, defaultSequence.size))
        assertEquals(1 to 0, splashSequenceStep(1, defaultSequence.size))
    }

    @Test fun boundingKeepsAspectRatioAndDoesNotUpscaleSmallPictures() {
        assertEquals(1071 to 1920, splashImageDimensions(1142, 2048))
        assertEquals(1920 to 1080, splashImageDimensions(7680, 4320))
        assertEquals(1 to 1920, splashImageDimensions(1, 100000))
        assertEquals(640 to 480, splashImageDimensions(640, 480))
    }

    @Test fun sampleSizeBoundsIntermediateDecodeForVeryLargeImages() {
        for (edge in listOf(1, 1920, 3840, 100000, Int.MAX_VALUE)) {
            val sample = splashSampleSize(edge, edge)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            val decodedEdge = (edge.toLong() + sample - 1) / sample
            assertTrue(decodedEdge <= SPLASH_IMAGE_MAX_EDGE * 2L)
            assertTrue(decodedEdge * decodedEdge <= 4_000_000L)
        }
        assertEquals(1, splashSampleSize(1142, 2048))
    }

    @Test fun allExifRotationsAndReflectionsAreRepresented() {
        assertEquals(SplashImageOrientation(0f, false), splashImageOrientation(1))
        assertEquals(SplashImageOrientation(0f, true), splashImageOrientation(2))
        assertEquals(SplashImageOrientation(180f, false), splashImageOrientation(3))
        assertEquals(SplashImageOrientation(180f, true), splashImageOrientation(4))
        assertEquals(SplashImageOrientation(90f, true), splashImageOrientation(5))
        assertEquals(SplashImageOrientation(90f, false), splashImageOrientation(6))
        assertEquals(SplashImageOrientation(270f, true), splashImageOrientation(7))
        assertEquals(SplashImageOrientation(270f, false), splashImageOrientation(8))
        assertEquals(SplashImageOrientation(0f, false), splashImageOrientation(99))
    }
}
