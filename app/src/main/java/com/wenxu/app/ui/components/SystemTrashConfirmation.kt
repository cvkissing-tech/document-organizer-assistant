package com.wenxu.app.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.wenxu.app.core.trash.SystemConfirmationRequest

/** Launches Android's own confirmation sheet once for each pending operation. */
@Composable
fun SystemTrashConfirmationEffect(
    request: SystemConfirmationRequest?,
    onResult: (operationId: Long, approved: Boolean) -> Unit,
) {
    val latestRequest by rememberUpdatedState(request)
    val latestOnResult by rememberUpdatedState(onResult)
    val launchedOperationIds = remember { mutableSetOf<Long>() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        latestRequest?.let { current ->
            launchedOperationIds.remove(current.operationId)
            latestOnResult(current.operationId, result.resultCode == Activity.RESULT_OK)
        }
    }

    LaunchedEffect(request?.operationId) {
        request ?: return@LaunchedEffect
        if (launchedOperationIds.add(request.operationId)) {
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }
}
