package com.opdash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opdash.ui.ConnectionViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContent {

            MaterialTheme {

                val vm: ConnectionViewModel = viewModel()

                var text by remember {
                    mutableStateOf("")
                }
                var password by remember {

                    mutableStateOf("")

                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {

                    Text(
                        "OPDash",
                        style = MaterialTheme.typography.headlineMedium
                    )

                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(

                        value = text,

                        onValueChange = {

                            text = it

                            vm.setSSID(it)

                        },

                        label = {

                            Text("Dashboard Code / SSID")

                        }


                    )
                    OutlinedTextField(

                        value = password,

                        onValueChange = {

                            password = it

                            vm.setPassword(it)

                        },

                        label = {

                            Text("WiFi Password")

                        }

                    )

                    Spacer(Modifier.height(20.dp))

                    Button(

                        enabled = text.isNotBlank(),

                        onClick = {

                            vm.connect()

                        }

                    ) {

                        Text("Connect")

                    }

                    Spacer(Modifier.height(30.dp))

                    Text(
                        "State"
                    )

                    Text(vm.state.name)

                    Spacer(Modifier.height(10.dp))

                    Text(vm.statusMessage)

                }

            }

        }

    }

}