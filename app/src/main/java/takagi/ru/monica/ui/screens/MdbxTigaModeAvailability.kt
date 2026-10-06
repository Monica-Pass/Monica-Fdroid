package takagi.ru.monica.ui.screens

import takagi.ru.monica.data.MdbxTigaMode

/** Client choices are independent of the modes supported by the MDBX format. */
internal fun mdbxTigaModesForCreation(): List<MdbxTigaMode> =
    listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER)

internal fun requireMdbxTigaCreationMode(mode: MdbxTigaMode) {
    require(mode in mdbxTigaModesForCreation()) { "Glitter is not supported by the Android client." }
}
