package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** Shared by the password and wallet address editors; storage remains owned by the caller. */
@Composable
fun EntryAddressFields(
    street: String, city: String, region: String, postalCode: String, country: String,
    onStreet: (String) -> Unit, onCity: (String) -> Unit, onRegion: (String) -> Unit,
    onPostalCode: (String) -> Unit, onCountry: (String) -> Unit,
    additionalStreet: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EntryAddressField(street, onStreet, R.string.street_address, "street")
        additionalStreet?.invoke()
        EntryAddressField(city, onCity, R.string.city, "city")
        EntryAddressField(region, onRegion, R.string.state_province, "region")
        // Postal codes may contain letters and spaces (e.g. SW1A 1AA).
        EntryAddressField(postalCode, onPostalCode, R.string.postal_code, "postal")
        EntryAddressField(country, onCountry, R.string.country, "country")
    }
}

@Composable
private fun EntryAddressField(value: String, onChange: (String) -> Unit, label: Int, tag: String) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(stringResource(label)) },
        singleLine = true, shape = RoundedCornerShape(12.dp), entryContentStyle = true,
        modifier = Modifier.fillMaxWidth().testTag("entry_address_$tag"))
}
