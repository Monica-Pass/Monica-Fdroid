package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomField
import takagi.ru.monica.data.model.TotpData

@Composable
fun EntryPaymentDetails(number: String, holder: String, expiry: String, cvv: String,
    onCreateSend: ((title: String, text: String) -> Unit)? = null) {
    EntryInformationDetails(listOf(
        Triple(stringResource(R.string.field_card_number), number, true),
        Triple(stringResource(R.string.cardholder_name), holder, false),
        Triple(stringResource(R.string.field_expiry), expiry, false),
        Triple(stringResource(R.string.cvv), cvv, true),
    ), onCreateSend)
}

@Composable
fun EntryAddressDetails(street: String, city: String, region: String, postalCode: String,
    country: String, secondStreet: String = "") {
    EntryInformationDetails(listOf(
        Triple(stringResource(R.string.street_address), listOf(street, secondStreet).filter { it.isNotBlank() }.joinToString("\n"), false),
        Triple(stringResource(R.string.city), city, false),
        Triple(stringResource(R.string.state_province), region, false),
        Triple(stringResource(R.string.postal_code), postalCode, false),
        Triple(stringResource(R.string.country), country, false),
    ))
}

@Composable
fun EntryContactDetails(emails: List<String>, phones: List<String>,
    onCreateSend: ((title: String, text: String) -> Unit)? = null) {
    EntryInformationDetails(emails.map { Triple(stringResource(R.string.email), it, false) } +
        phones.map { Triple(stringResource(R.string.phone), it, false) }, onCreateSend)
}

@Composable
private fun EntryInformationDetails(values: List<Triple<String, String, Boolean>>,
    onCreateSend: ((title: String, text: String) -> Unit)? = null) {
    CustomFieldDetailRows(values.filter { it.second.isNotBlank() }.mapIndexed { index, value ->
        CustomField(id = index.toLong(), entryId = 0, title = value.first,
            value = value.second, isProtected = value.third)
    }, onCreateSend = onCreateSend)
}

/** Existing authenticator metadata is readable in either editor style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpContentDetailSheet(title: String, data: TotpData, notes: String,
    onDismiss: () -> Unit, onEdit: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 24.dp)
            .testTag("totp_content_detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            DetailCardSurface {
                Column(Modifier.padding(16.dp)) {
            EntryInformationDetails(listOf(
                Triple(stringResource(R.string.issuer), data.issuer, false),
                Triple(stringResource(R.string.account_name), data.accountName, false),
                Triple(stringResource(R.string.notes), notes, false),
            ))
                }
            }
            onEdit?.let { edit ->
                FilledTonalButton(onClick = { onDismiss(); edit() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }
}
