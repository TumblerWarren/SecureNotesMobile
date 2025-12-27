package expo.modules.securestorage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.core.interfaces.ActivityProvider

class SecureStorageModule : Module() {
  private var currentPromise: Promise? = null
  private val PICK_FILE_REQUEST_CODE = 4242

  override fun definition() = ModuleDefinition {
    Name("SecureStorage")

    AsyncFunction("pickFile") { promise: Promise ->
      val activity = appContext.currentActivity ?: throw Exception("Activity not found")
      currentPromise = promise

      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/octet-stream" // Can be specific text/plain or application/x-sqlite3
        putExtra(Intent.EXTRA_TITLE, "secure_notes.db")
        // IMPORTANT: Request persistable read/write permissions
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      }

      activity.startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
    }

    AsyncFunction("createFile") { filename: String, promise: Promise ->
      val activity = appContext.currentActivity ?: throw Exception("Activity not found")
      currentPromise = promise

      val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/x-sqlite3" // Or application/octet-stream
        putExtra(Intent.EXTRA_TITLE, filename)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      }

      activity.startActivityForResult(intent, PICK_FILE_REQUEST_CODE) // We can reuse the same code or make a new one. reusing is fine if logic is same.
    }

    AsyncFunction("readFile") { uriStr: String, promise: Promise ->
      try {
        val uri = Uri.parse(uriStr)
        val resolver = appContext.reactContext?.contentResolver ?: throw Exception("Context not found")
        
        resolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            promise.resolve(base64)
        } ?: throw Exception("Could not open input stream")
      } catch (e: Exception) {
        promise.reject("READ_ERROR", e.message, e)
      }
    }

    AsyncFunction("saveFile") { uriStr: String, base64Data: String, promise: Promise ->
      try {
        val uri = Uri.parse(uriStr)
        val resolver = appContext.reactContext?.contentResolver ?: throw Exception("Context not found")
        
        resolver.openOutputStream(uri, "wt")?.use { outputStream ->
          val bytes = Base64.decode(base64Data, Base64.DEFAULT)
          outputStream.write(bytes)
        }
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("SAVE_ERROR", e.message, e)
      }
    }

    // Handle the result from the activity
    OnActivityResult { _, payload -> 
      if (payload.requestCode == PICK_FILE_REQUEST_CODE) {
        val intent = payload.data
        if (payload.resultCode == Activity.RESULT_OK && intent != null) {
          intent.data?.let { uri ->
            val resolver = appContext.reactContext?.contentResolver
            
            // TAKE PERSISTABLE PERMISSION
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver?.takePersistableUriPermission(uri, flags)
            
            currentPromise?.resolve(uri.toString())
          } ?: run {
            currentPromise?.reject("NO_URI", "No URI returned", null)
          }
        } else {
          currentPromise?.resolve(null) // Canceled
        }
        currentPromise = null
      }
    }
  }
}
