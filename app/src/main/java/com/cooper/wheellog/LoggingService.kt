package com.cooper.wheellog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.cooper.wheellog.data.TripDao
import com.cooper.wheellog.utils.Constants
import com.cooper.wheellog.utils.FileUtil
import com.cooper.wheellog.utils.NotificationUtil
import com.cooper.wheellog.utils.ParserLogToWheelData
import com.cooper.wheellog.utils.PermissionsUtil.checkExternalFilePermission
import com.cooper.wheellog.utils.PermissionsUtil.checkLocationPermission
import com.cooper.wheellog.utils.RideLogCsv
import com.welie.blessed.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class LoggingService : Service() {
    private val appConfig: AppConfig by inject()
    private val notifications: NotificationUtil by inject()
    private val dao: TripDao by inject()
    private var mLocation: Location? = null
    private var mLastLocation: Location? = null
    private var mLocationDistance = 0.0
    private val mLocationManager: LocationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private var mLocationProvider = LocationManager.NETWORK_PROVIDER
    private var logLocationData = false
    private lateinit var fileUtil: FileUtil
    private var ioState = CoroutineScope(Dispatchers.IO + Job())
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RideLogWriter").apply { isDaemon = true }
    }
    private val writerScope = CoroutineScope(SupervisorJob() + writerExecutor.asCoroutineDispatcher())
    private val rideWrites = Channel<() -> Unit>(Channel.UNLIMITED)
    private val acceptLock = Any()
    private var acceptRows = false
    private var writerJob: Job? = null

    fun updateConnectionState(connectionState: ConnectionState) {
        if (logLocationData) {
            if (connectionState == ConnectionState.CONNECTED) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                mLocationManager.requestLocationUpdates(
                    mLocationProvider,
                    250,
                    0f,
                    locationListener
                )
            } else {
                mLocationManager.removeUpdates(locationListener)
            }
        }
    }

    private val mBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent): IBinder? {
        if (WheelData.getInstance() == null) {
            stopSelf()
            return null
        }
        instance = this
        fileUtil = FileUtil(applicationContext)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (!checkExternalFilePermission(this)) {
                showToast(R.string.logging_error_no_storage_permission)
                stopSelf()
                return mBinder
            }
            if (!isExternalStorageReadable || !isExternalStorageWritable) {
                showToast(R.string.logging_error_storage_unavailable)
                stopSelf()
                return mBinder
            }
        }
        logLocationData = appConfig.logLocationData
        if (logLocationData && !checkLocationPermission(this)) {
            showToast(R.string.logging_error_no_location_permission)
            logLocationData = false
        }
        var writeToLastLog = false
        val mac = WheelData.getInstance().mac
        if (appConfig.continueThisDayLog &&
            appConfig.continueThisDayLogMacException != mac
        ) {
            val lastFileUtil = FileUtil.getLastLog(applicationContext)
            // An older date,time log is a different schema. Start a new file.
            if (lastFileUtil != null &&
                lastFileUtil.file?.path?.contains(mac.replace(':', '_')) == true &&
                lastFileUtil.inputStream?.use { RideLogCsv.isCurrentLog(it) } == true
            ) {
                fileUtil = lastFileUtil
                // parse prev log for filling wheeldata values
                val parser = ParserLogToWheelData()
                parser.parseFile(fileUtil)
                fileUtil.prepareStream()
                writeToLastLog = true
                // reset trip duration for recalculation in trip list
                ioState.launch {
                    dao.getTripByFileName(fileUtil.file!!.name)?.apply {
                        duration = 0
                        dao.update(this)
                    }
                }
            }
        }
        if (!writeToLastLog) {
            val sdFormatter = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            val filename = sdFormatter.format(Date()) + ".csv"
            if (!fileUtil.prepareFile(filename, WheelData.getInstance().mac)) {
                stopSelf()
                return mBinder
            }
            appConfig.continueThisDayLogMacException = ""
        }
        if (logLocationData) {
            val isGPSEnabled = mLocationManager
                .isProviderEnabled(LocationManager.GPS_PROVIDER)

            // Getting Network Provider status
            val isNetworkEnabled = mLocationManager
                .isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            // Getting if the users wants to use GPS
            var useGPS = appConfig.useGps
            if (!isGPSEnabled && !isNetworkEnabled) {
                logLocationData = false
                showToast(R.string.logging_error_all_location_providers_disabled)
            } else if (useGPS && !isGPSEnabled) {
                useGPS = false
                showToast(R.string.logging_error_gps_disabled)
            } else if (!useGPS && !isNetworkEnabled) {
                logLocationData = false
                showToast(R.string.logging_error_network_disabled)
            }
            if (logLocationData) {
                mLocation = lastBestLocation
                mLocationProvider = LocationManager.NETWORK_PROVIDER
                if (useGPS) {
                    mLocationProvider = LocationManager.GPS_PROVIDER
                }
                // Acquire a reference to the system Location Manager
                mLocationManager.requestLocationUpdates(
                    mLocationProvider,
                    250,
                    0f,
                    locationListener
                )
            }
        }
        fileUtil.enableLineBuffer()
        if (!writeToLastLog) {
            val offsetMillis = TimeZone.getDefault().getOffset(System.currentTimeMillis())
            fileUtil.writeLine(RideLogCsv.preamble(offsetMillis))
            fileUtil.writeLine(RideLogCsv.HEADER)
            // The header is flushed now so a crash still leaves a readable file.
            fileUtil.flush()
        }
        startRideWriter()
        val serviceIntent = Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED)
        serviceIntent.putExtra(
            Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION,
            fileUtil.absolutePath
        )
        serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true)
        sendBroadcast(serviceIntent)
        Timber.i("DataLogger Started")

        return mBinder
    }

    private fun isNullOrEmpty(s: String?): Boolean {
        return s == null || s.trim { it <= ' ' }.isEmpty()
    }

    override fun onDestroy() {
        var isBusy = false
        if (logLocationData && mLastLocation != null) {
            appConfig.lastLocationLaltitude = mLastLocation!!.latitude
            appConfig.lastLocationLongitude = mLastLocation!!.longitude
        }
        val path = fileUtil.absolutePath
        // Formatting happens on RideLogWriter. Drain it before the file is closed and uploaded.
        finishRideWriter()
        fileUtil.close()
        Timber.wtf("DataLogger Stopping...")
        notifications.setCustomTitle("Uploading tack...")

        // electro.club upload
        if (fileUtil.fileName != "" && appConfig.autoUploadEc) {
            isBusy = true
            try {
                Timber.wtf("Uploading %s to electro.club", fileUtil.fileName)
                val data = fileUtil.readBytes() ?: return
                ElectroClub.instance.uploadTrack(
                    data,
                    fileUtil.fileName,
                    true
                ) { success: Boolean? ->
                    if (!success!!) {
                        Timber.wtf("Upload failed...")
                        notifications.setCustomTitle("Upload failed.")
                    }
                    reallyDestroy(null)
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Timber.wtf("Error upload log to electro.club: %s", e.toString())
                reallyDestroy(path)
            }
        }
        if (!isBusy) {
            reallyDestroy(path)
        }
    }

    private fun reallyDestroy(path: String?) {
        val serviceIntent = Intent(Constants.ACTION_LOGGING_SERVICE_TOGGLED)
        if (!isNullOrEmpty(path)) {
            serviceIntent.putExtra(Constants.INTENT_EXTRA_LOGGING_FILE_LOCATION, path)
        }
        serviceIntent.putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false)
        sendBroadcast(serviceIntent)
        try {
            if (logLocationData) {
                mLocationManager.removeUpdates(locationListener)
            }
        } catch (ignored: Exception) {
        }
        instance = null
        Timber.wtf("DataLogger Stopped")
    }

    private val isExternalStorageWritable: Boolean
        /* Checks if external storage is available for read and write */
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state
        }
    private val isExternalStorageReadable: Boolean
        /* Checks if external storage is available to at least read */
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state || Environment.MEDIA_MOUNTED_READ_ONLY == state
        }

    fun updateFile() {
        synchronized(acceptLock) {
            if (!acceptRows) {
                return
            }
            val sample = captureSample()
            // toLine() runs on RideLogWriter. The main thread only queued the copied values.
            rideWrites.trySend {
                fileUtil.writeLine(sample.toLine())
            }
        }
    }

    private fun captureSample(): RideSample {
        val wd = WheelData.getInstance()
        var latitude: Double? = null
        var longitude: Double? = null
        var gpsSpeed: Double? = null
        var gpsAlt: Double? = null
        var gpsBearing: Float? = null
        if (logLocationData && mLocation != null) {
            longitude = mLocation!!.longitude
            latitude = mLocation!!.latitude
            gpsSpeed = mLocation!!.speed * 3.6
            gpsAlt = mLocation!!.altitude
            gpsBearing = mLocation!!.bearing
            if (mLastLocation != null) mLocationDistance += mLastLocation!!.distanceTo(mLocation!!)
                .toDouble()
            mLastLocation = mLocation
        }
        return RideSample(
            timeUnixMs = wd.timeStamp,
            latitude = latitude,
            longitude = longitude,
            gpsSpeed = gpsSpeed,
            gpsAltitude = gpsAlt,
            gpsHeading = gpsBearing,
            gpsDistanceMeters = mLocationDistance,
            speed = wd.speedDouble,
            voltage = wd.voltageDouble,
            phaseCurrent = wd.phaseCurrentDouble,
            current = wd.currentDouble,
            power = wd.powerDouble,
            torque = wd.torque,
            pwm = wd.calculatedPwm,
            batteryLevel = wd.batteryLevel,
            distance = wd.distance,
            totalDistance = wd.totalDistance,
            systemTemp = wd.temperature,
            temp2 = wd.temperature2,
            tilt = wd.angle,
            roll = wd.roll,
            mode = wd.modeStr,
            alert = wd.alert,
        )
    }

    private fun startRideWriter() {
        writerJob = writerScope.launch {
            for (write in rideWrites) {
                try {
                    write()
                } catch (ex: Exception) {
                    Timber.wtf("Ride log write failed: %s", ex.toString())
                }
            }
        }
        synchronized(acceptLock) {
            acceptRows = true
        }
    }

    private fun finishRideWriter() {
        synchronized(acceptLock) {
            acceptRows = false
            rideWrites.close()
        }
        runBlocking {
            writerJob?.join()
        }
        writerScope.cancel()
        writerExecutor.shutdown()
    }

    // Define a listener that responds to location updates
    private var locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            mLocation = location
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Need for old API level 28 and lower.")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
    }

    private val lastBestLocation: Location?
        @SuppressLint("MissingPermission") get() {
            val locationGPS = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val locationNet =
                mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            var GPSLocationTime: Long = 0
            var NetLocationTime: Long = 0
            if (locationGPS != null) {
                GPSLocationTime = locationGPS.time
            }
            if (locationNet != null) {
                NetLocationTime = locationNet.time
            }
            return if (GPSLocationTime - NetLocationTime > 0) {
                locationGPS
            } else {
                locationNet
            }
        }

    private fun showToast(messageId: Int) {
        for (i in 0..3) Toast.makeText(this, messageId, Toast.LENGTH_LONG).show()
    }

    private class RideSample(
        val timeUnixMs: Long,
        val latitude: Double?,
        val longitude: Double?,
        val gpsSpeed: Double?,
        val gpsAltitude: Double?,
        val gpsHeading: Float?,
        val gpsDistanceMeters: Double,
        val speed: Double,
        val voltage: Double,
        val phaseCurrent: Double,
        val current: Double,
        val power: Double,
        val torque: Double,
        val pwm: Double,
        val batteryLevel: Int,
        val distance: Int,
        val totalDistance: Long,
        val systemTemp: Int,
        val temp2: Int,
        val tilt: Double,
        val roll: Double,
        val mode: String?,
        val alert: String?,
    ) {
        fun toLine(): String {
            return RideLogCsv.dataLine(
                timeUnixMs = timeUnixMs,
                latitude = latitude?.toString() ?: "",
                longitude = longitude?.toString() ?: "",
                gpsSpeed = gpsSpeed?.toString() ?: "",
                gpsAltitude = gpsAltitude?.toString() ?: "",
                gpsHeading = gpsHeading?.toString() ?: "",
                gpsDistanceMeters = gpsDistanceMeters,
                speed = speed,
                voltage = voltage,
                phaseCurrent = phaseCurrent,
                current = current,
                power = power,
                torque = torque,
                pwm = pwm,
                batteryLevel = batteryLevel,
                distance = distance,
                totalDistance = totalDistance,
                systemTemp = systemTemp,
                temp2 = temp2,
                tilt = tilt,
                roll = roll,
                mode = mode,
                alert = alert,
            )
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): LoggingService {
            return this@LoggingService
        }
    }

    companion object {
        private var instance: LoggingService? = null
        fun isInstanceCreated(): Boolean {
            return instance != null
        }
    }
}