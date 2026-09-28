package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** UI shared with the wallet; callers keep their existing expiry representation and save pipeline. */
@Composable
fun EntryPaymentFields(
    number: String, holder: String, expiry: String, cvv: String,
    onNumber: (String) -> Unit, onHolder: (String) -> Unit,
    onExpiry: (String) -> Unit, onCvv: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PaymentField(number, { onNumber(EntryPaymentFormat.cardNumber(it)) }, R.string.field_card_number, "number", true, true)
        PaymentField(holder, onHolder, R.string.cardholder_name, "holder", false, false)
        PaymentField(expiry, { onExpiry(EntryPaymentFormat.expiry(it)) }, R.string.field_expiry, "expiry", false, false)
        PaymentField(cvv, { onCvv(EntryPaymentFormat.cvv(it)) }, R.string.cvv, "cvv", true, true)
    }
}

@Composable
private fun PaymentField(value: String, onChange: (String) -> Unit, label: Int, tag: String,
    secret: Boolean, numeric: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) }, singleLine = true, entryContentStyle = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {{
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    stringResource(if (visible) R.string.hide_password else R.string.show_password))
            }
        }} else null,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("entry_payment_$tag"))
}
