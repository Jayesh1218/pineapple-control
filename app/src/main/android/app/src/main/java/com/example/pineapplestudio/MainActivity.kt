package com.example.pineapplestudio

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private var isListening by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready for voice command")
    private var outputLog by mutableStateOf("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Audio permission is required for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { statusMessage = "Listening..." }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { statusMessage = "Processing..." }
            override fun onError(error: Int) {
                isListening = false
                statusMessage = "Error occurred ($error). Tap to speak."
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    statusMessage = "Command: $command"
                    executeSshCommand(command)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        setContent {
            Surface(
                modifier = Modifier.FillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(
                    status = statusMessage,
                    log = outputLog,
                    isListening = isListening,
                    onMicClick = { toggleListening() }
                )
            }
        }
    }

    private fun toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
            statusMessage = "Stopped listening."
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer.startListening(intent)
            isListening = true
            statusMessage = "Listening..."
        }
    }

    private fun executeSshCommand(command: String) {
        // Mapping voice commands to actual Pineapple shell commands
        val shellCommand = when {
            command.contains("scan", ignoreCase = true) -> "airodump-ng wlan0mon"
            command.contains("status", ignoreCase = true) -> "ifconfig"
            command.contains("reboot", ignoreCase = true) -> "reboot"
            else -> command // fallback to run the raw text command
        }

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val jsch = JSch()
                val session = jsch.getSession("root", "172.16.42.1", 22)
                session.setPassword("root") // Default WiFi Pineapple password
                session.setConfig("StrictHostKeyChecking", "no")
                session.connect(5000)

                val channel = session.openChannel("exec") as ChannelExec
                channel.command = shellCommand
                channel.connect()

                val reader = channel.inputStream.bufferedReader()
                val result = reader.readText()

                channel.disconnect()
                session.disconnect()

                withContext(Dispatchers.Main) {
                    outputLog = "Exec: $shellCommand\n$result"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    outputLog = "SSH Error: ${e.localizedMessage}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }
}

@Composable
fun MainScreen(status: String, log: String, isListening: Boolean, onMicClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WiFi Pineapple Voice Control",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 24.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).padding(vertical = 16.dp)
        ) {
            Text(text = status, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(text = log.ifEmpty { "Logs will appear here..." }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Button(
            onClick = onMicClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(text = if (isListening) "Listening... (Tap to Stop)" else "Tap to Speak Command")
        }
    }
}
