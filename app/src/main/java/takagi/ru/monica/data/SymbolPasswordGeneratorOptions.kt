package takagi.ru.monica.data

data class SymbolPasswordGeneratorOptions(
    val length: Int,
    val includeUppercase: Boolean,
    val includeLowercase: Boolean,
    val includeNumbers: Boolean,
    val includeSymbols: Boolean,
    val allowedSymbols: String,
    val excludeSimilar: Boolean,
    val excludeAmbiguous: Boolean,
    val uppercaseMin: Int,
    val lowercaseMin: Int,
    val numbersMin: Int,
    val symbolsMin: Int,
)

fun GeneratorPreferences.toSymbolPasswordGeneratorOptions(): SymbolPasswordGeneratorOptions {
    val resolvedSymbols = if (useSymbolExclusionMode) {
        GeneratorPreferences.DEFAULT_SYMBOLS.filterNot { it in excludedSymbols }
    } else {
        customSymbols
    }
    return SymbolPasswordGeneratorOptions(
        length = symbolLength.coerceIn(4, 128),
        includeUppercase = includeUppercase,
        includeLowercase = includeLowercase,
        includeNumbers = includeNumbers,
        includeSymbols = includeSymbols,
        allowedSymbols = resolvedSymbols,
        excludeSimilar = excludeSimilar,
        excludeAmbiguous = excludeAmbiguous,
        uppercaseMin = uppercaseMin.coerceAtLeast(0),
        lowercaseMin = lowercaseMin.coerceAtLeast(0),
        numbersMin = numbersMin.coerceAtLeast(0),
        symbolsMin = symbolsMin.coerceAtLeast(0),
    )
}
