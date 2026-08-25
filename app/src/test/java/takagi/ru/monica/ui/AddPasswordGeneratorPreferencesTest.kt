package takagi.ru.monica.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.GeneratorPreferences
import takagi.ru.monica.data.toSymbolPasswordGeneratorOptions

class AddPasswordGeneratorPreferencesTest {

    @Test
    fun addPasswordGeneratorUsesAllHomepageSymbolDefaults() {
        val options = GeneratorPreferences(
            symbolLength = 40,
            includeUppercase = false,
            includeLowercase = true,
            includeNumbers = true,
            includeSymbols = true,
            useSymbolExclusionMode = false,
            customSymbols = "@#",
            excludeSimilar = true,
            excludeAmbiguous = true,
            uppercaseMin = 0,
            lowercaseMin = 4,
            numbersMin = 3,
            symbolsMin = 2
        ).toSymbolPasswordGeneratorOptions()

        assertEquals(40, options.length)
        assertFalse(options.includeUppercase)
        assertTrue(options.includeLowercase)
        assertEquals("@#", options.allowedSymbols)
        assertTrue(options.excludeSimilar)
        assertTrue(options.excludeAmbiguous)
        assertEquals(4, options.lowercaseMin)
        assertEquals(3, options.numbersMin)
        assertEquals(2, options.symbolsMin)
    }

    @Test
    fun homepageSymbolExclusionModeIsResolvedOnceForEveryGeneratorSurface() {
        val options = GeneratorPreferences(
            useSymbolExclusionMode = true,
            excludedSymbols = "!@",
            customSymbols = "ignored"
        ).toSymbolPasswordGeneratorOptions()

        assertFalse(options.allowedSymbols.contains('!'))
        assertFalse(options.allowedSymbols.contains('@'))
        assertTrue(options.allowedSymbols.isNotEmpty())
    }

    @Test
    fun addPasswordDialogReadsDefaultsWithoutWritingTemporaryChangesBack() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readText()
        val dialogSource = source.substringAfter("private fun PasswordGeneratorDialog(")

        assertTrue(source.contains("GeneratorPreferencesManager(context.applicationContext)"))
        assertTrue(source.contains("generatorPreferencesManager.preferencesFlow.collectAsState"))
        assertTrue(source.contains("generatorPreferences = generatorPreferences"))
        assertTrue(dialogSource.contains("generatorPreferences.toSymbolPasswordGeneratorOptions()"))
        assertTrue(dialogSource.contains("AdvancedPasswordGenerator.generatePassword("))
        assertFalse(dialogSource.contains("GeneratorPreferencesManager"))
        assertFalse(dialogSource.contains(".save("))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
