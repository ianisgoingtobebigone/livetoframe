package app.livetoframe.personal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: GalleryModel
    private val permissionPreferences by lazy { getSharedPreferences("photo-permissions", MODE_PRIVATE) }
    private var requestingPhotos = false
    private var requestingLocation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[GalleryModel::class.java]
        setContent {
            val state by model.state.collectAsState()
            val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                requestingPhotos = false
                val access = photoAccess()
                if (result.isNotEmpty()) {
                    val needsSettings = access == PhotoAccess.NONE &&
                        !shouldShowRequestPermissionRationale(photoPermissions()[0])
                    permissionPreferences.edit().putBoolean("needsSettings", needsSettings).apply()
                }
                model.refresh(access)
            }
            val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                requestingLocation = false
                val access = photoAccess()
                if (access != model.state.value.access) model.refresh(access)
                model.finishPermissionRequest(has(Manifest.permission.ACCESS_MEDIA_LOCATION))
            }
            val trashConfirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                model.finishTrash(it.resultCode == Activity.RESULT_OK, photoAccess())
            }
            fun requestPhotos() {
                if (requestingPhotos) return
                requestingPhotos = true
                photoPermission.launch(photoPermissions())
            }
            LaunchedEffect(state.trashIntent) {
                val intent = model.takeTrashIntent() ?: return@LaunchedEffect
                try {
                    trashConfirmation.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                } catch (_: Exception) {
                    model.finishTrash(false, photoAccess(), launchFailed = true)
                }
            }
            MaterialTheme(colorScheme = galleryColors(isSystemInDarkTheme()), typography = galleryTypography) {
                GalleryScreen(
                    state = state,
                    model = model,
                    requestAccess = {
                        if (photoAccess() == PhotoAccess.NONE && permissionPreferences.getBoolean("needsSettings", false) &&
                            !shouldShowRequestPermissionRationale(photoPermissions()[0])) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                        } else requestPhotos()
                    },
                    refresh = { model.refresh(photoAccess()) },
                    save = { resumeBatch ->
                        if (has(Manifest.permission.ACCESS_MEDIA_LOCATION)) model.save(resumeBatch)
                        else if (!requestingLocation) {
                            requestingLocation = true
                            model.deferSave(resumeBatch)
                            locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.recheckTrash()
        val access = photoAccess()
        if (!requestingPhotos && !requestingLocation &&
            (access != model.state.value.access || access == PhotoAccess.LIMITED)) {
            model.refresh(access)
        }
    }

    private fun has(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun photoAccess(): PhotoAccess = when {
        has(photoPermissions()[0]) -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.LIMITED
        else -> PhotoAccess.NONE
    }

}
