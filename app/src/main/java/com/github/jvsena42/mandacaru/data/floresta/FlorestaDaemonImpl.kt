package com.github.jvsena42.mandacaru.data.floresta

import android.util.Log
import com.florestad.AssumeUtreexoValue
import com.florestad.AssumeValidArg
import com.florestad.Config
import com.florestad.Florestad
import com.github.jvsena42.mandacaru.BuildConfig
import com.github.jvsena42.mandacaru.common.runSuspendCatching
import com.github.jvsena42.mandacaru.data.PreferenceKeys
import com.github.jvsena42.mandacaru.data.PreferencesDataSource
import com.github.jvsena42.mandacaru.domain.floresta.FlorestaDaemon
import com.github.jvsena42.mandacaru.domain.model.Constants
import com.github.jvsena42.mandacaru.presentation.utils.SnapshotCodec
import com.github.jvsena42.mandacaru.presentation.utils.WalletBirthday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

import com.florestad.Network as FlorestaNetwork

class FlorestaDaemonImpl(
    private val datadir: String,
    private val preferencesDataSource: PreferencesDataSource
) : FlorestaDaemon {

    private var isRunning = false
    private var daemon: Florestad? = null

    override suspend fun start() {
        if (isRunning) return
        runSuspendCatching {
            val config = createConfig()
            
            Log.i(TAG, "start: creating Florestad")
            daemon = Florestad.fromConfig(config)
            
            Log.i(TAG, "start: Florestad created")
            
            Log.i(TAG, "start: calling daemon.start()")
            daemon?.start()
            
            Log.i(TAG, "start: daemon.start() returned")
            
            isRunning = true
            Log.i(TAG, "start: Floresta running")
        }.onFailure { e ->
            Log.e(TAG, "start error: ", e)
            isRunning = false
        }
    }

    private suspend fun createConfig(): Config {
        val pendingSnapshot = preferencesDataSource
            .getString(PreferenceKeys.PENDING_UTREEXO_SNAPSHOT, "")
            .takeIf { it.isNotEmpty() }

        val network = preferencesDataSource.getString(
            PreferenceKeys.CURRENT_NETWORK,
            FlorestaNetwork.BITCOIN.name
        ).toFlorestaNetwork()

        val filtersStartHeight = if (network == FlorestaNetwork.BITCOIN) {
            val year = preferencesDataSource
                .getString(PreferenceKeys.WALLET_BIRTHDAY_YEAR, "")
                .toIntOrNull()
                ?: WalletBirthday.defaultYear()
            WalletBirthday.bitcoinHeightForYear(year)
        } else null

        val userAgent =
            "/Floresta:${Constants.FLORESTA_VERSION}/mandacaru:${BuildConfig.VERSION_NAME}/"

        val builtinSnapshotJson = builtinSnapshotFor(network)

        val startupSnapshotJson = pendingSnapshot ?: builtinSnapshotJson

        val snapshotSource = when {
            pendingSnapshot != null -> "pending"
            builtinSnapshotJson != null -> "builtin"
            else -> "floresta-default"
        }

        val effectiveDataDir = dataDirFor(
            network,
            preferencesDataSource.getString(
                PreferenceKeys.FLORESTA_DIRECTORY,
                ""
            ).takeIf { it.isNotBlank() }
        )

        val rpcPort = rpcPortFor(network)
        val assumeUtreexoValue = startupSnapshotJson?.let { toAssumeUtreexoValue(it) }

        Log.i(
            TAG,
            "createConfig: snapshotSource=$snapshotSource, " +
                "snapshotApplied=${assumeUtreexoValue != null}, " +
                "network=$network, datadir=$effectiveDataDir, rpcPort=$rpcPort, " +
                "filtersStartHeight=$filtersStartHeight, userAgent=$userAgent",
        )

        return Config(
            datadir = effectiveDataDir,
            network = network,
            assumeValid = AssumeValidArg.Hardcoded,
            cfilters = true,
            filtersStartHeight = filtersStartHeight,
            jsonRpcAddress = "127.0.0.1:$rpcPort",
            logToFile = true,
            assumeUtreexo = true,
            assumeutreexoValue = assumeUtreexoValue,
            userAgent = userAgent,
        )
    }

    override suspend fun stop() {
        if (!isRunning) return
        try {
            daemon?.stop()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.e(TAG, "stop error: ", e)
        } finally {
            isRunning = false
            daemon = null
        }
    }

    override fun isRunning(): Boolean = isRunning

    override suspend fun dumpUtreexoState(): Result<String> = withContext(Dispatchers.IO) {
        val d = daemon
        if (!isRunning || d == null) {
            return@withContext Result.failure(IllegalStateException("Daemon not running"))
        }
        runSuspendCatching { d.dumpUtreexoState() }
    }

    override suspend fun prepareForSnapshotImport(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isRunning) {
            return@withContext Result.failure(
                IllegalStateException("Daemon is still running; call stop() first")
            )
        }

        val network = preferencesDataSource.getString(
            PreferenceKeys.CURRENT_NETWORK,
            FlorestaNetwork.BITCOIN.name
        ).toFlorestaNetwork()

        val base = File(
            dataDirFor(
                network,
                preferencesDataSource.getString(
                    PreferenceKeys.FLORESTA_DIRECTORY,
                    ""
                ).takeIf { it.isNotBlank() }
            )
        )

        listOf("chaindata", "cfilters").forEach { sub ->
            val dir = File(base, sub)
            val size = if (dir.exists()) dirSize(dir) else 0L
            Log.i(TAG, "prepareForSnapshotImport: preserving $sub (size=$size)")
        }

        Result.success(Unit)
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun builtinSnapshotFor(network: FlorestaNetwork): String? {
        if (network != FlorestaNetwork.BITCOIN) return null
        return runCatching {
            SnapshotCodec.normalizeToJson(Constants.BUILTIN_UTREEXO_SNAPSHOT_COMPACT)
        }.onFailure {
            Log.w(TAG, "builtin snapshot decode failed; falling back to Floresta default", it)
        }.getOrNull()
    }

    private fun rpcPortFor(network: FlorestaNetwork): String = when (network) {
        FlorestaNetwork.BITCOIN -> Constants.RPC_PORT_MAINNET
        FlorestaNetwork.SIGNET -> Constants.RPC_PORT_SIGNET
        FlorestaNetwork.TESTNET -> Constants.RPC_PORT_TESTNET
        FlorestaNetwork.TESTNET4 -> Constants.RPC_PORT_TESTNET_4
        FlorestaNetwork.REGTEST -> Constants.RPC_PORT_REGTEST
    }

    private fun toAssumeUtreexoValue(json: String): AssumeUtreexoValue? = runCatching {
        val obj = JSONObject(json)
        val rootsArray = obj.getJSONArray("roots")
        val roots = List(rootsArray.length()) { rootsArray.getString(it) }

        AssumeUtreexoValue(
            blockHash = obj.getString("block_hash"),
            height = obj.getLong("height").toUInt(),
            roots = roots,
            leaves = obj.getLong("leaves").toULong(),
        )
    }.onFailure {
        Log.w(TAG, "failed to decode snapshot JSON to AssumeUtreexoValue", it)
    }.getOrNull()

    private fun dataDirFor(
        network: FlorestaNetwork,
        selectedDir: String?
    ): String {
        val usingCustomDir = !selectedDir.isNullOrBlank()
        val baseDir = if (usingCustomDir) File(selectedDir!!) else File(datadir)
    
        Log.i(
            TAG,
            buildString {
                append("dataDirFor: ")
                append("usingCustomDir=").append(usingCustomDir)
                append(", selectedDir=").append(selectedDir ?: "<internal>")
                append(", base=").append(baseDir.absolutePath)
                append(", exists=").append(baseDir.exists())
                append(", isDirectory=").append(baseDir.isDirectory)
                append(", canRead=").append(baseDir.canRead())
                append(", canWrite=").append(baseDir.canWrite())
            }
        )
    
        if (!baseDir.exists()) {
            val created = baseDir.mkdirs()
            Log.i(
                TAG,
                "dataDirFor: mkdirs()=$created existsNow=${baseDir.exists()}"
            )
        }
    
        if (network == FlorestaNetwork.BITCOIN) {
            Log.i(
                TAG,
                "dataDirFor: final=${baseDir.absolutePath}"
            )
            return baseDir.absolutePath
        }
    
        val dir = File(baseDir, network.dirName())
    
        if (!dir.exists()) {
            val created = dir.mkdirs()
            Log.i(
                TAG,
                "dataDirFor: created network dir ${dir.absolutePath} success=$created"
            )
        }
    
        Log.i(
            TAG,
            buildString {
                append("dataDirFor: final=").append(dir.absolutePath)
                append(", exists=").append(dir.exists())
                append(", canRead=").append(dir.canRead())
                append(", canWrite=").append(dir.canWrite())
            }
        )
    
        return dir.absolutePath
    }

    private fun FlorestaNetwork.dirName(): String = when (this) {
        FlorestaNetwork.BITCOIN -> "bitcoin"
        FlorestaNetwork.SIGNET -> "signet"
        FlorestaNetwork.TESTNET -> "testnet"
        FlorestaNetwork.TESTNET4 -> "testnet4"
        FlorestaNetwork.REGTEST -> "regtest"
    }

    companion object {
        private const val TAG = "FlorestaDaemonImpl"
    }
}
