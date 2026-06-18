package com.wallpaper.changer.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.sqrt

@Composable
fun SafeFolderSetupDialog(
    onDismiss: () -> Unit,
    onSetupComplete: (lockType: String, lockValue: String?) -> Unit
) {
    var selectedType by remember { mutableStateOf("PIN") } // "PIN", "Gesture", "Device"
    var pinValue by remember { mutableStateOf("") }
    var pinConfirmValue by remember { mutableStateOf("") }
    var confirmStep by remember { mutableStateOf(false) }

    var gesturePattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var gestureConfirmPattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    
    var errorMsg by remember { mutableStateOf("") }

    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
        title = {
            Text(
                "Configure Vault Lock",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!confirmStep) {
                    // Lock Type Selector
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Choose Lock Type", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("PIN", "Gesture", "Device").forEach { type ->
                                val isSelected = selectedType == type
                                Button(
                                    onClick = { 
                                        selectedType = type
                                        errorMsg = ""
                                        pinValue = ""
                                        pinConfirmValue = ""
                                        gesturePattern = emptyList()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    val label = when(type) {
                                        "Device" -> "Device Lock"
                                        else -> type
                                    }
                                    Text(label, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }

                // Configuration details per Lock Type
                when (selectedType) {
                    "PIN" -> {
                        val header = if (confirmStep) "Confirm your 4-digit PIN" else "Enter a 4-digit PIN"
                        Text(header, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        
                        val activePin = if (confirmStep) pinConfirmValue else pinValue
                        
                        // Dots
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            repeat(4) { idx ->
                                val filled = idx < activePin.length
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(
                                            color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            shape = CircleShape
                                        )
                                )
                            }
                        }

                        // PIN Keyboard
                        PinKeypad(
                            onDigit = { digit ->
                                val current = if (confirmStep) pinConfirmValue else pinValue
                                if (current.length < 4) {
                                    if (confirmStep) {
                                        pinConfirmValue += digit
                                    } else {
                                        pinValue += digit
                                    }
                                }
                            },
                            onBackspace = {
                                if (confirmStep) {
                                    if (pinConfirmValue.isNotEmpty()) pinConfirmValue = pinConfirmValue.dropLast(1)
                                } else {
                                    if (pinValue.isNotEmpty()) pinValue = pinValue.dropLast(1)
                                }
                            },
                            onClear = {
                                if (confirmStep) {
                                    pinConfirmValue = ""
                                } else {
                                    pinValue = ""
                                }
                            }
                        )
                    }
                    "Gesture" -> {
                        val header = if (confirmStep) "Redraw gesture to confirm" else "Draw unlock gesture (connect dots)"
                        Text(header, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

                        GesturePatternCanvas(
                            onPatternComplete = { pattern ->
                                if (pattern.size >= 3) {
                                    if (confirmStep) {
                                        gestureConfirmPattern = pattern
                                    } else {
                                        gesturePattern = pattern
                                    }
                                    errorMsg = ""
                                } else {
                                    errorMsg = "Connect at least 3 dots."
                                }
                            }
                        )

                        if (gesturePattern.isNotEmpty()) {
                            Text(
                                "Pattern recorded (${gesturePattern.size} points)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    "Device" -> {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Device Lock Required",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Uses your existing system lock screen configuration (Biometrics, PIN, Password, or Pattern) to secure this vault.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedType) {
                        "PIN" -> {
                            val activeVal = if (confirmStep) pinConfirmValue else pinValue
                            if (activeVal.length < 4) {
                                errorMsg = "PIN must be exactly 4 digits."
                                return@Button
                            }
                            if (!confirmStep) {
                                confirmStep = true
                            } else {
                                if (pinValue == pinConfirmValue) {
                                    onSetupComplete("PIN", pinValue)
                                } else {
                                    errorMsg = "PINs do not match. Try again."
                                    pinConfirmValue = ""
                                    confirmStep = false
                                    pinValue = ""
                                }
                            }
                        }
                        "Gesture" -> {
                            if (gesturePattern.isEmpty()) {
                                errorMsg = "Please draw a pattern."
                                return@Button
                            }
                            if (!confirmStep) {
                                confirmStep = true
                            } else {
                                if (gesturePattern == gestureConfirmPattern) {
                                    val valStr = gesturePattern.joinToString(",")
                                    onSetupComplete("Gesture", valStr)
                                } else {
                                    errorMsg = "Gestures do not match. Try again."
                                    gestureConfirmPattern = emptyList()
                                    confirmStep = false
                                    gesturePattern = emptyList()
                                }
                            }
                        }
                        "Device" -> {
                            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                            if (!km.isDeviceSecure) {
                                errorMsg = "Please set up a secure screen lock on your device first."
                            } else {
                                onSetupComplete("Device", null)
                            }
                        }
                    }
                }
            ) {
                val btnText = if (confirmStep || selectedType == "Device") "Confirm & Lock" else "Next"
                Text(btnText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (confirmStep) {
                        confirmStep = false
                        pinConfirmValue = ""
                        gestureConfirmPattern = emptyList()
                        errorMsg = ""
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Text(if (confirmStep) "Back" else "Cancel")
            }
        }
    )
}

@Composable
fun SafeFolderUnlockDialog(
    albumName: String,
    lockType: String,
    lockValue: String?,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    var pinValue by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    val context = LocalContext.current

    // Handle Device unlock natively
    if (lockType == "Device") {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onUnlocked()
            } else {
                onCancel()
            }
        }

        LaunchedEffect(Unit) {
            val intent = km.createConfirmDeviceCredentialIntent("Unlock Vault", "Access album '$albumName'")
            if (intent != null) {
                launcher.launch(intent)
            } else {
                onUnlocked() // Fallback if screen lock was disabled
            }
        }

        // Render loading / secure overlay while system intent runs
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Card(
                modifier = Modifier.padding(24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Confirm credentials to view Vault...", textAlign = TextAlign.Center)
                }
            }
        }
    } else {
        Dialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .systemBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Vault", style = MaterialTheme.typography.titleLarge)
                    }

                    // Content Area
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f).wrapContentHeight(Alignment.CenterVertically)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Locked Folder",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Unlock '$albumName'",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Provide credential to access vault.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (errorMsg.isNotEmpty()) {
                            Text(
                                errorMsg,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        when (lockType) {
                            "PIN" -> {
                                // Dots indicator
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                ) {
                                    repeat(4) { idx ->
                                        val filled = idx < pinValue.length
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .background(
                                                    color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                    shape = CircleShape
                                                )
                                        )
                                    }
                                }

                                PinKeypad(
                                    onDigit = { digit ->
                                        if (pinValue.length < 4) {
                                            pinValue += digit
                                            if (pinValue.length == 4) {
                                                if (pinValue == lockValue) {
                                                    onUnlocked()
                                                } else {
                                                    errorMsg = "Incorrect PIN. Try again."
                                                    pinValue = ""
                                                }
                                            } else {
                                                errorMsg = ""
                                            }
                                        }
                                    },
                                    onBackspace = {
                                        if (pinValue.isNotEmpty()) pinValue = pinValue.dropLast(1)
                                        errorMsg = ""
                                    },
                                    onClear = {
                                        pinValue = ""
                                        errorMsg = ""
                                    }
                                )
                            }
                            "Gesture" -> {
                                GesturePatternCanvas(
                                    onPatternComplete = { pattern ->
                                        val valStr = pattern.joinToString(",")
                                        if (valStr == lockValue) {
                                            onUnlocked()
                                        } else {
                                            errorMsg = "Incorrect gesture. Try again."
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Bottom empty spacer to keep header aligned
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
fun PinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.wrapContentSize()
    ) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫")
        )

        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { item ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .clip(CircleShape)
                            .clickable {
                                when (item) {
                                    "⌫" -> onBackspace()
                                    "C" -> onClear()
                                    else -> onDigit(item)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GesturePatternCanvas(
    onPatternComplete: (List<Int>) -> Unit
) {
    var points = remember { mutableStateListOf<Offset>() }
    var currentTouch by remember { mutableStateOf<Offset?>(null) }
    var gridPoints = remember { mutableStateListOf<Offset>() }
    val pathConnectedIndices = remember { mutableStateListOf<Int>() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val primaryColorAlpha = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val defaultDotColor = Color.Gray.copy(alpha = 0.5f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val connectedRadiusPx = with(density) { 14.dp.toPx() }
    val defaultRadiusPx = with(density) { 8.dp.toPx() }
    val lineStrokePx = with(density) { 6.dp.toPx() }

    Canvas(
        modifier = Modifier
            .size(280.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        pathConnectedIndices.clear()
                        points.clear()
                        currentTouch = offset
                        
                        // Check if we hit a dot
                        val idx = getClosestDotIndex(offset, gridPoints, size.width.toFloat())
                        if (idx != -1) {
                            pathConnectedIndices.add(idx)
                            points.add(gridPoints[idx])
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newTouch = change.position
                        currentTouch = newTouch
                        
                        val idx = getClosestDotIndex(newTouch, gridPoints, size.width.toFloat())
                        if (idx != -1 && !pathConnectedIndices.contains(idx)) {
                            pathConnectedIndices.add(idx)
                            points.add(gridPoints[idx])
                        }
                    },
                    onDragEnd = {
                        onPatternComplete(pathConnectedIndices.toList())
                        currentTouch = null
                    },
                    onDragCancel = {
                        currentTouch = null
                        pathConnectedIndices.clear()
                        points.clear()
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height

        // Calculate 3x3 grid coordinates
        if (gridPoints.isEmpty()) {
            val cellW = width / 4
            val cellH = height / 4
            for (row in 1..3) {
                for (col in 1..3) {
                    gridPoints.add(Offset(col * cellW, row * cellH))
                }
            }
        }

        // Draw background grid dots
        gridPoints.forEachIndexed { index, offset ->
            val isConnected = pathConnectedIndices.contains(index)
            drawCircle(
                color = if (isConnected) primaryColor else defaultDotColor,
                radius = if (isConnected) connectedRadiusPx else defaultRadiusPx,
                center = offset
            )
        }

        // Draw connecting lines
        if (points.isNotEmpty()) {
            for (i in 0 until points.size - 1) {
                drawLine(
                    color = primaryColor,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = lineStrokePx
                )
            }
            currentTouch?.let { touch ->
                drawLine(
                    color = primaryColorAlpha,
                    start = points.last(),
                    end = touch,
                    strokeWidth = lineStrokePx
                )
            }
        }
    }
}

private fun getClosestDotIndex(touch: Offset, gridPoints: List<Offset>, canvasWidth: Float): Int {
    if (gridPoints.isEmpty()) return -1
    val threshold = canvasWidth / 8
    gridPoints.forEachIndexed { index, pt ->
        val dx = touch.x - pt.x
        val dy = touch.y - pt.y
        val dist = sqrt((dx * dx + dy * dy).toDouble())
        if (dist <= threshold) {
            return index
        }
    }
    return -1
}
