package com.jacobibanez.plugin.android.godotplaygameservices.snapshots

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.games.AnnotatedData
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.SnapshotsClient
import com.google.android.gms.games.SnapshotsClient.EXTRA_SNAPSHOT_METADATA
import com.google.android.gms.games.SnapshotsClient.RESOLUTION_POLICY_HIGHEST_PROGRESS
import com.google.android.gms.games.SnapshotsClient.RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED
import com.google.android.gms.games.SnapshotsClient.DataOrConflict
import com.google.android.gms.games.SnapshotsClient.SnapshotConflict
import com.google.android.gms.games.snapshot.Snapshot
import com.google.android.gms.games.snapshot.SnapshotMetadata
import com.google.android.gms.games.snapshot.SnapshotMetadataBuffer
import com.google.android.gms.games.snapshot.SnapshotMetadataChange
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import com.jacobibanez.plugin.android.godotplaygameservices.BuildConfig.GODOT_PLUGIN_NAME
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.conflictEmitted
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.snapshotDeleted
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.gameLoadFailed
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.gameLoaded
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.gameSaved
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.snapshotsLoadFailed
import com.jacobibanez.plugin.android.godotplaygameservices.signals.SnapshotSignals.snapshotsLoaded
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin.emitSignal

private enum class Origin {
    SAVE, LOAD
}

class SnapshotsProxy(
    private val godot: Godot,
    private val snapshotsClient: SnapshotsClient = PlayGames.getSnapshotsClient(godot.getActivity()!!)
) {
    private val tag = SnapshotsProxy::class.java.simpleName

    private val showSavedGamesRequestCode = 9010
    private val snapshotNotFoundCode = 26570

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == showSavedGamesRequestCode && resultCode == Activity.RESULT_OK) {
            data?.let { intent ->
                if (intent.hasExtra(EXTRA_SNAPSHOT_METADATA)) {
                    val snapshotMetadata = intent.extras
                        ?.get(EXTRA_SNAPSHOT_METADATA) as SnapshotMetadata
                    loadGame(snapshotMetadata.uniqueName, false)
                }
            }
        }
    }

    fun showSavedGames(
        title: String,
        allowAddButton: Boolean,
        allowDelete: Boolean,
        maxSnapshots: Int
    ) {
        Log.d(tag, "Showing save games")
        snapshotsClient.getSelectSnapshotIntent(title, allowAddButton, allowDelete, maxSnapshots)
            .addOnSuccessListener { intent ->
                ActivityCompat.startActivityForResult(
                    godot.getActivity()!!, intent,
                    showSavedGamesRequestCode, null
                )
            }
    }

    fun saveGame(
        fileName: String,
        description: String,
        saveData: ByteArray,
        playedTimeMillis: Long,
        progressValue: Long,
        resolutionPolicy: Int = RESOLUTION_POLICY_HIGHEST_PROGRESS
    ) {
        Log.d(tag, "Saving game data with name $fileName and description ${description}.")
        try {
            snapshotsClient.open(fileName, true, resolutionPolicy)
                .addOnCompleteListener { task ->
                    onSnapshotOpenedForSave(
                        task,
                        fileName,
                        description,
                        saveData,
                        playedTimeMillis,
                        progressValue
                    )
                }
        } catch (e: Exception) {
            Log.e(tag, "Error while opening Snapshot $fileName for saving.", e)
            emitGameSaved(false, fileName, description)
        }
    }

    private fun onSnapshotOpenedForSave(
        task: Task<DataOrConflict<Snapshot>>,
        fileName: String,
        description: String,
        saveData: ByteArray,
        playedTimeMillis: Long,
        progressValue: Long
    ) {
        // Set while this listener owns an open snapshot that must be discarded on failure.
        var openSnapshot: Snapshot? = null
        try {
            if (!task.isSuccessful) {
                Log.e(
                    tag,
                    "Error while opening Snapshot $fileName for saving. Cause: ${task.exception}"
                )
                emitGameSaved(false, fileName, description)
                return
            }
            val dataOrConflict = task.result
            if (dataOrConflict.isConflict) {
                emitConflict(Origin.SAVE.name, dataOrConflict.conflict)
                emitGameSaved(false, fileName, description)
                return
            }
            val snapshot = dataOrConflict.data
                ?: throw IllegalStateException("Opened Snapshot $fileName has no data.")
            openSnapshot = snapshot
            if (!snapshot.snapshotContents.writeBytes(saveData)) {
                throw IllegalStateException("Could not write data to Snapshot $fileName.")
            }
            val metadata = SnapshotMetadataChange.Builder().apply {
                setDescription(description)
                setPlayedTimeMillis(playedTimeMillis)
                setProgressValue(progressValue)
            }.build()

            val commitTask = snapshotsClient.commitAndClose(snapshot, metadata)
            openSnapshot = null
            commitTask.addOnCompleteListener { result ->
                if (!result.isSuccessful) {
                    Log.e(
                        tag,
                        "Error while committing Snapshot $fileName. Cause: ${result.exception}"
                    )
                }
                emitGameSaved(result.isSuccessful, fileName, description)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error while saving Snapshot $fileName.", e)
            openSnapshot?.let { discardQuietly(it) }
            emitGameSaved(false, fileName, description)
        }
    }

    fun loadGame(
        fileName: String,
        createIfNotFound: Boolean,
        resolutionPolicy: Int = RESOLUTION_POLICY_MOST_RECENTLY_MODIFIED
    ) {
        Log.d(tag, "Loading snapshot with name $fileName.")
        try {
            snapshotsClient.open(fileName, createIfNotFound, resolutionPolicy)
                .addOnCompleteListener { task -> onSnapshotOpenedForLoad(task, fileName) }
        } catch (e: Exception) {
            Log.e(tag, "Error while opening Snapshot $fileName for loading.", e)
            emitGameLoadFailed(fileName, statusCodeOf(e))
        }
    }

    private fun onSnapshotOpenedForLoad(task: Task<DataOrConflict<Snapshot>>, fileName: String) {
        try {
            if (!task.isSuccessful) {
                val exception = task.exception
                Log.e(
                    tag,
                    "Error while opening Snapshot $fileName for loading. Cause: $exception"
                )
                if (exception is ApiException && exception.statusCode == snapshotNotFoundCode) {
                    emitSignal(
                        godot,
                        GODOT_PLUGIN_NAME,
                        gameLoaded,
                        Gson().toJson(null)
                    )
                } else {
                    emitGameLoadFailed(fileName, statusCodeOf(exception))
                }
                return
            }
            val dataOrConflict = task.result
            if (dataOrConflict.isConflict) {
                emitConflict(Origin.LOAD.name, dataOrConflict.conflict)
                return
            }
            val snapshot = dataOrConflict.data
                ?: throw IllegalStateException("Opened Snapshot $fileName has no data.")
            // The contents are copied out, so the snapshot can be closed before emitting.
            val json = try {
                Gson().toJson(fromSnapshot(godot, snapshot))
            } finally {
                discardQuietly(snapshot)
            }
            emitSignal(godot, GODOT_PLUGIN_NAME, gameLoaded, json)
        } catch (e: Exception) {
            Log.e(tag, "Error while loading Snapshot $fileName.", e)
            emitGameLoadFailed(fileName, statusCodeOf(e))
        }
    }

    fun loadSnapshots(forceReload: Boolean) {
        Log.d(tag, "Loading snapshots")
        try {
            snapshotsClient.load(forceReload)
                .addOnCompleteListener { task -> onSnapshotsLoaded(task) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load snapshots.", e)
            emitSnapshotsLoadFailed(statusCodeOf(e))
        }
    }

    private fun onSnapshotsLoaded(task: Task<AnnotatedData<SnapshotMetadataBuffer>>) {
        try {
            if (!task.isSuccessful) {
                Log.e(
                    tag,
                    "Failed to load snapshots. Cause: ${task.exception}",
                    task.exception
                )
                emitSnapshotsLoadFailed(statusCodeOf(task.exception))
                return
            }
            Log.d(
                tag,
                "Snapshots loaded successfully. Data is stale? ${task.result.isStale}"
            )
            val snapshots = task.result.get()
                ?: throw IllegalStateException("Loaded snapshots have no data.")
            val result: List<Dictionary> = try {
                snapshots.map { snapshotMetadata ->
                    fromSnapshotMetadata(godot, snapshotMetadata)
                }.toList()
            } finally {
                snapshots.release()
            }
            emitSignal(
                godot,
                GODOT_PLUGIN_NAME,
                snapshotsLoaded,
                Gson().toJson(result)
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to load snapshots.", e)
            emitSnapshotsLoadFailed(statusCodeOf(e))
        }
    }

    fun deleteSnapshot(snapshotId: String) {
        Log.d(tag, "Attempting to delete snapshot with id: $snapshotId")
        try {
            snapshotsClient.load(true)
                .addOnCompleteListener { task -> onSnapshotsLoadedForDelete(task, snapshotId) }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load snapshots before delete", e)
            emitDeleteResult(false, snapshotId)
        }
    }

    private fun onSnapshotsLoadedForDelete(
        task: Task<AnnotatedData<SnapshotMetadataBuffer>>,
        snapshotId: String
    ) {
        try {
            if (!task.isSuccessful) {
                Log.e(tag, "Failed to load snapshots before delete", task.exception)
                emitDeleteResult(false, snapshotId)
                return
            }
            val snapshotMetadata = task.result.get()
                ?.firstOrNull { it.snapshotId == snapshotId }

            if (snapshotMetadata != null) {
                performDelete(snapshotMetadata, snapshotId)
            } else {
                Log.w(tag, "Snapshot with id $snapshotId not found")
                emitDeleteResult(false, snapshotId)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete snapshot $snapshotId", e)
            emitDeleteResult(false, snapshotId)
        }
    }

    private fun performDelete(metadata: SnapshotMetadata, snapshotId: String) {
        snapshotsClient.delete(metadata)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(tag, "Successfully deleted snapshot: ${task.result}")
                    emitDeleteResult(true, task.result ?: snapshotId)
                } else {
                    Log.e(tag, "Failed to delete snapshot $snapshotId", task.exception)
                    emitDeleteResult(false, snapshotId)
                }
            }
    }

    private fun discardQuietly(snapshot: Snapshot) {
        try {
            snapshotsClient.discardAndClose(snapshot)
        } catch (e: Exception) {
            Log.w(tag, "Error while discarding Snapshot.", e)
        }
    }

    private fun statusCodeOf(exception: Exception?): Int =
        (exception as? ApiException)?.statusCode ?: CommonStatusCodes.ERROR

    private fun emitGameSaved(isSaved: Boolean, fileName: String, description: String) {
        emitSignal(
            godot,
            GODOT_PLUGIN_NAME,
            gameSaved,
            isSaved,
            fileName,
            description
        )
    }

    private fun emitGameLoadFailed(fileName: String, statusCode: Int) {
        emitSignal(
            godot,
            GODOT_PLUGIN_NAME,
            gameLoadFailed,
            fileName,
            statusCode
        )
    }

    private fun emitSnapshotsLoadFailed(statusCode: Int) {
        emitSignal(
            godot,
            GODOT_PLUGIN_NAME,
            snapshotsLoadFailed,
            statusCode
        )
    }

    private fun emitDeleteResult(success: Boolean, snapshotId: String) {
        emitSignal(
            godot,
            GODOT_PLUGIN_NAME,
            snapshotDeleted,
            success,
            snapshotId
        )
    }

    /**
     * Emits the [conflictEmitted] signal. Throws if the conflict is missing or can't be read, so
     * the caller reports a failure instead.
     */
    private fun emitConflict(origin: String, conflict: SnapshotConflict?) {
        checkNotNull(conflict) { "Snapshot conflict has no data." }
        val snapshot = conflict.snapshot
        val fileName = snapshot.metadata.uniqueName
        val description = snapshot.metadata.description
        Log.e(
            tag, "Conflict with id ${conflict.conflictId} during $origin of data with " +
                    "name $fileName and description ${description}."
        )
        val json = Gson().toJson(fromConflict(godot, origin, conflict))
        emitSignal(
            godot,
            GODOT_PLUGIN_NAME,
            conflictEmitted,
            json
        )
    }
}
