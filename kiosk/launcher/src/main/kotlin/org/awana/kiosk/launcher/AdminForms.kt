package org.awana.kiosk.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun NewPinFields(onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val tooShort = pin.isNotEmpty() && pin.length < MIN_PIN_LENGTH
    val mismatch = confirm.isNotEmpty() && pin != confirm
    val valid = pin.length >= MIN_PIN_LENGTH && pin == confirm

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.pin_new)) },
            isError = tooShort,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag(TAG_PIN_NEW),
        )
        if (tooShort) {
            Text(stringResource(R.string.pin_too_short, MIN_PIN_LENGTH))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.pin_confirm)) },
            isError = mismatch,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag(TAG_PIN_CONFIRM),
        )
        if (mismatch) {
            Text(stringResource(R.string.pin_mismatch))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onSubmit(pin) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().testTag(TAG_PIN_SAVE),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
fun AddNetworkFields(onAdd: (ssid: String, passphrase: String) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text(stringResource(R.string.wifi_ssid)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TAG_WIFI_SSID),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text(stringResource(R.string.wifi_passphrase)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag(TAG_WIFI_PASSPHRASE),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onAdd(ssid, passphrase) },
            enabled = ssid.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag(TAG_WIFI_ADD),
        ) {
            Text(stringResource(R.string.wifi_add))
        }
    }
}

@Composable
fun InstallUrlFields(enabled: Boolean, onInstall: (url: String, packageName: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var packageName by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text(stringResource(R.string.install_url_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(TAG_INSTALL_URL),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = packageName,
            onValueChange = { packageName = it.trim() },
            label = { Text(stringResource(R.string.install_package_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(TAG_INSTALL_PACKAGE),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onInstall(url, packageName) },
            enabled = enabled && url.isNotBlank() && packageName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag(TAG_INSTALL_GO),
        ) {
            Text(stringResource(R.string.action_install))
        }
    }
}

private const val MIN_PIN_LENGTH = 4

const val TAG_PIN_NEW = "pin-new"
const val TAG_PIN_CONFIRM = "pin-confirm"
const val TAG_PIN_SAVE = "pin-save"
const val TAG_WIFI_SSID = "wifi-ssid"
const val TAG_WIFI_PASSPHRASE = "wifi-passphrase"
const val TAG_WIFI_ADD = "wifi-add"
const val TAG_INSTALL_URL = "install-url"
const val TAG_INSTALL_PACKAGE = "install-package"
const val TAG_INSTALL_GO = "install-go"
