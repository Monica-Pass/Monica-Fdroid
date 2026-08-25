package takagi.ru.monica.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.GeneratorPreferences

class AutofillGeneratorPreferencesTest {

    @Test
    fun homepageSymbolPreferencesAreUsedByAutofillGenerator() {
        val options = GeneratorPreferences(
            symbolLength = 36,
            includeUppercase = true,
            includeLowercase = false,
            includeNumbers = true,
            includeSymbols = false,
            useSymbolExclusionMode = false,
            customSymbols = "@$",
            excludeSimilar = true,
            excludeAmbiguous = false,
            uppercaseMin = 2,
            lowercaseMin = 0,
            numbersMin = 3,
            symbolsMin = 0
        ).toAutofillPasswordGeneratorOptions()

        assertEquals(36, options.length)
        assertTrue(options.includeUppercase)
        assertFalse(options.includeLowercase)
        assertTrue(options.includeNumbers)
        assertFalse(options.includeSymbols)
        assertEquals("@$", options.allowedSymbols)
        assertTrue(options.excludeSimilar)
        assertFalse(options.excludeAmbiguous)
        assertTrue(options.readableMode)
        assertEquals(2, options.uppercaseMin)
        assertEquals(3, options.numbersMin)
    }

    @Test
    fun exclusionModeUsesDefaultSymbolsWithoutExcludedCharacters() {
        val options = GeneratorPreferences(
            useSymbolExclusionMode = true,
            excludedSymbols = "!@",
            customSymbols = "ignored"
        ).toAutofillPasswordGeneratorOptions()

        assertFalse(options.allowedSymbols.orEmpty().contains('!'))
        assertFalse(options.allowedSymbols.orEmpty().contains('@'))
        assertTrue(options.allowedSymbols.orEmpty().isNotEmpty())
    }
}
