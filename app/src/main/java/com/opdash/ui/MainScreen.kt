package com.opdash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opdash.logging.Logger

@Composable
fun MainScreen(
    vm: MainViewModel
) {

    var ssid by remember {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(
            text = "OPDash",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = ssid,
            onValueChange = {

                ssid = it
                vm.updateDashboardSSID(it)

            },
            modifier = Modifier.fillMaxWidth(),
            label = {

                Text("Dashboard SSID")

            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(

            onClick = {

                vm.connect()

            },

            enabled = ssid.isNotBlank()

        ) {

            Text("Connect")

        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "State",
            style = MaterialTheme.typography.titleMedium
        )

        Text(vm.state.name)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Status",
            style = MaterialTheme.typography.titleMedium
        )

        Text(vm.status)

        Spacer(modifier = Modifier.height(20.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Logs",
            style = MaterialTheme.typography.titleMedium
        )

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {

            items(Logger.logs) { log ->

                Text(log)

            }

        }

    }

}