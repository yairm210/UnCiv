package com.unciv.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.ActivityCompat.startActivityForResult
import com.badlogic.gdx.Gdx
import com.unciv.logic.GameSaver
import com.unciv.ui.utils.IImportExport
import com.unciv.ui.utils.ImportExportParameters
import com.unciv.ui.utils.ImportExportStatus
import java.io.File
import kotlin.concurrent.thread

class ImportExportAndroid(private val activity: Activity): IImportExport {
    companion object {
        const val REQUESTCODE_EXPORT = 4201     // arbitrary choice
        const val REQUESTCODE_IMPORT = 4202
    }

    private var parameters = ImportExportParameters()
    private var callback: ((status:ImportExportStatus, msg:String)->Unit)? = null

    fun activityResult(resultCode: Int, requestCode: Int, uri: Uri) {
        println("activityResult: resultCode=$resultCode, requestCode=$requestCode, uri=$uri")
        if (requestCode != REQUESTCODE_EXPORT && requestCode != REQUESTCODE_IMPORT) return
        if (resultCode == Activity.RESULT_CANCELED) {
            callback?.invoke(ImportExportStatus.Failure, "Cancelled")
        }
        if (resultCode != Activity.RESULT_OK) return

        thread(start = true,  name = "ImportExport", isDaemon = false) {
            try {
                callback?.invoke(ImportExportStatus.Progress, "Working")
                if (parameters.config) copyItem(requestCode, uri, GameSaver.settingsFileName)
                if (parameters.autosave) copyItem(requestCode, uri, GameSaver.saveFilesFolder + File.separator + GameSaver.autosaveName)
                callback?.invoke(ImportExportStatus.Progress, "{Working}.")
                if (parameters.saves) copyItem(requestCode, uri, GameSaver.saveFilesFolder, true)
                callback?.invoke(ImportExportStatus.Progress, "{Working}..")
                if (parameters.maps) copyItem(requestCode, uri, "maps")
                callback?.invoke(ImportExportStatus.Progress, "{Working}...")
                if (parameters.mods) copyItem(requestCode, uri, "mods")
                callback?.invoke(ImportExportStatus.Progress, "{Working}....")
                if (parameters.music) copyItem(requestCode, uri, "music")
                callback?.invoke(ImportExportStatus.Success, "OK")
            } catch (ex: Exception) {
                println("Import/Export exception: ${ex.localizedMessage}")
                ex.printStackTrace()
                callback?.invoke(ImportExportStatus.Failure, "Sorry, there was an error.")
            }
        }
    }

    private fun copyItem(requestCode: Int, uri: Uri, name: String, exceptAuto:Boolean = false) {
        val localPath = activity.filesDir.path + File.separator + name
        val exportPath = uri.path + File.separator + name
        val fromFile = File(if(requestCode== REQUESTCODE_EXPORT) localPath else exportPath)
        val toFile = File(if(requestCode== REQUESTCODE_EXPORT) exportPath else localPath)
        if (!fromFile.exists()) return
        if (fromFile.isFile) {
            fromFile.copyTo(toFile, true)
        } else if (exceptAuto)  {
            fromFile.listFiles()?.forEach { file ->
                if ( file.isFile && !file.name.startsWith(GameSaver.autosaveName) ) {
                    val fileTo = File(toFile.path + File.separator + file.name)
                    file.copyTo(fileTo, true)
                }
            }
        } else {
            fromFile.copyRecursively(toFile, true)
        }
    }

    private fun startRequest(requestCode: Int, params: ImportExportParameters, notify: ((status:ImportExportStatus, msg:String)->Unit)?) {
        if (!isSupported()) return
        if (!(params.config || params.autosave || params.saves || params.maps)) return
        parameters = params
        callback = notify
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (Build.VERSION.SDK_INT >= 26)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, "Documents/Unciv")

        startActivityForResult(activity, intent, requestCode, null)
    }

    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 21

    override fun requestExport(params: ImportExportParameters, notify: ((status:ImportExportStatus, msg:String)->Unit)?) {
        startRequest(REQUESTCODE_EXPORT, params, notify)
    }

    override fun requestImport(params: ImportExportParameters, notify: ((status:ImportExportStatus, msg:String)->Unit)?) {
        startRequest(REQUESTCODE_IMPORT, params, notify)
    }

}
