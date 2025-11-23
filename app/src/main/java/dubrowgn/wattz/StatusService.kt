package dubrowgn.wattz

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.util.LruCache
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class StatusService : Service() {
    private lateinit var battery: Battery
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var indicatorUnits: String? = null
    private lateinit var noteBuilder: Notification.Builder
    private lateinit var noteMgr: NotificationManager
    private var pluggedInAt: ZonedDateTime? = null
    private lateinit var snapshot: BatterySnapshot
    private val task = PeriodicTask({ update() }, intervalMs)

    // Professional icon caching
    private val iconCache = LruCache<String, Icon>(200) // Increased for safety + prewarm
    private var prewarmedPercentageIcons = false
    private var cacheVersion = 0

    private fun debug(msg: String) {
        Log.d(this::class.java.name, msg)
    }

    private inner class MsgReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                batteryDataReq -> updateData()
                settingsUpdateInd -> {
                    loadSettings()
                    update()
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    pluggedInAt = ZonedDateTime.now()
                    update()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    pluggedInAt = null
                    update()
                }
                Intent.ACTION_SCREEN_OFF -> task.stop()
                Intent.ACTION_SCREEN_ON -> task.start()
            }
        }
    }

    private fun loadSettings() {
        val settings = getSharedPreferences(settingsName, MODE_MULTI_PROCESS)
        val newScalar = settings.getFloat("currentScalar", 1f).toDouble()
        val newInvert = settings.getBoolean("invertCurrent", false)
        val newUnits = settings.getString("indicatorUnits", null)

        val needsCacheInvalidation =
            indicatorUnits != newUnits ||
            battery.currentScalar != newScalar ||
            battery.invertCurrent != newInvert

        // Apply new values
        battery.currentScalar = newScalar
        battery.invertCurrent = newInvert
        indicatorUnits = newUnits

        if (needsCacheInvalidation) {
            iconCache.evictAll()
            cacheVersion++
            prewarmedPercentageIcons = false  // Allow re-prewarm if switching back to %
        }
    }

    private fun init() {
        battery = Battery(applicationContext)
        snapshot = battery.snapshot()

        noteMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        noteMgr.createNotificationChannel(
            NotificationChannel(
                noteChannelId,
                "Power Status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Continuously displays current battery power consumption"
            }
        )

        val noteIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ind = getString(R.string.indeterminate)
        noteBuilder = Notification.Builder(this, noteChannelId)
            .setContentTitle("Battery Draw: $ind W")
            .setSmallIcon(renderIcon(ind, "W"))
            .setContentIntent(noteIntent)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        debug("onStartCommand()")

        super.onStartCommand(intent, flags, startId)

        init()
        loadSettings()

        // Pre-warm percentage icons if needed
        if (indicatorUnits == "%") {
            prewarmPercentageIcons()
        }

        task.start()

        try {
            updateNotificationIconAndTitle()  // Show real value immediately
            startForeground(noteId, noteBuilder.build())
        } catch (e: Exception) {
            Log.e(this::class.java.name, "Failed to start foreground", e)
        }

        registerReceiver(MsgReceiver(), IntentFilter().apply {
            addAction(batteryDataReq)
            addAction(settingsUpdateInd)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, RECEIVER_NOT_EXPORTED)

        return START_STICKY
    }

    private fun prewarmPercentageIcons() {
        if (prewarmedPercentageIcons) return

        debug("Pre-warming 0–100% icons...")
        val start = System.currentTimeMillis()

        for (i in 0..100) {
            renderIcon("$i", "")
        }

        debug("Pre-warmed 101 icons in ${System.currentTimeMillis() - start} ms")
        prewarmedPercentageIcons = true
    }

    private fun updateNotificationIconAndTitle() {
        snapshot = battery.snapshot()
        val value = fmt(snapshot.levelPercent)
        val title = "\( {getString(R.string.battery)} \){getString(R.string.chargeLevel)}: ${value}%"

        noteBuilder
            .setContentTitle(title)
            .setSmallIcon(renderIcon(value, ""))
    }

    override fun onDestroy() {
        try { unregisterReceiver(MsgReceiver()) } catch (_: Exception) {}
        task.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLowMemory() {
        super.onLowMemory()
        iconCache.evictAll()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            iconCache.evictAll()
        }
    }

    private fun renderIcon(value: String, unit: String): Icon {
        val normalizedValue = value.trim().let { if (it.length > 8) it.substring(0, 8) else it }
        val key = "$cacheVersion|$normalizedValue|\( unit| \){resources.displayMetrics.densityDpi}"

        return iconCache.get(key) ?: createIconBitmap(normalizedValue, unit).also {
            iconCache.put(key, it)
        }
    }

    private fun createIconBitmap(value: String, unit: String): Icon {
        val density = resources.displayMetrics.density
        val size = (48f * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        if (unit.isEmpty()) {
            // Large centered percentage
            paint.textSize = 42f * density
            val bounds = Rect()
            paint.getTextBounds(value, 0, value.length, bounds)
            val y = size / 2f - bounds.exactCenterY()
            canvas.drawText(value, size / 2f, y, paint)
        } else {
            // Two-line mode
            paint.textSize = 28f * density
            canvas.drawText(value, size / 2f, size * 0.55f, paint)

            paint.textSize = 18f * density
            paint.alpha = 200
            canvas.drawText(unit, size / 2f, size * 0.88f, paint)
        }

        return Icon.createWithBitmap(bitmap)
    }
    
    private fun updateData() {
        val plugType = snapshot.plugType?.name?.lowercase()
        val indeterminate = getString(R.string.indeterminate)
        val fullyCharged = getString(R.string.fullyCharged)
        val no = getString(R.string.no)
        val yes = getString(R.string.yes)

        val intent = Intent()
            .setPackage(packageName)
            .setAction(batteryDataResp)
            .putExtra("charging",
                when (snapshot.charging) {
                    true -> if (plugType == null) yes else "$yes ($plugType)"
                    false -> no
                }
            )
            .putExtra("chargeLevel", fmt(snapshot.levelPercent) + "%")
            .putExtra("chargingSince",
                when (val pluggedInAt = pluggedInAt) {
                    null -> indeterminate
                    else -> LocalDateTime
                        .ofInstant(pluggedInAt.toInstant(), pluggedInAt.zone)
                        .format(dateFmt)
                }
            )
            .putExtra("current", fmt(snapshot.amps) + "A")
            .putExtra("energy",
                "${fmt(snapshot.energyWattHours)}Wh (${fmt(snapshot.energyAmpHours)}Ah)"
            )
            .putExtra("power", fmt(snapshot.watts) + "W")
            .putExtra("temperature", fmt(snapshot.celsius) + "°C")
            .putExtra("timeToFullCharge",
                when (val seconds = snapshot.secondsUntilCharged) {
                    null -> indeterminate
                    0.0 -> fullyCharged
                    else -> fmtSeconds(seconds)
                }
            )
            .putExtra("voltage", fmt(snapshot.volts) + "V")

        applicationContext.sendBroadcast(intent)
    }

    private fun update() {
        debug("update()")

        snapshot = battery.snapshot()

        val txtLabel = when (indicatorUnits) {
            "A" -> getString(R.string.current)
            "Ah" -> getString(R.string.energy)
            "C" -> getString(R.string.temperature)
            "V" -> getString(R.string.voltage)
            "Wh" -> getString(R.string.energy)
            "%" -> getString(R.string.chargeLevel)
            else -> getString(R.string.power)
        }
        val txtValue = fmt( when (indicatorUnits) {
            "A" -> snapshot.amps
            "Ah" -> snapshot.energyAmpHours
            "C" -> snapshot.celsius
            "V" -> snapshot.volts
            "Wh" -> snapshot.energyWattHours
            "%" -> snapshot.levelPercent
            else -> snapshot.watts
        })
        val txtUnits = when (indicatorUnits) {
            "C" -> ""
            "Wh" -> ""
            "%" -> ""
            else -> indicatorUnits ?: "W"
        }

        val title = if (indicatorUnits == "%") {
            "${getString(R.string.battery)} ${txtLabel}: ${txtValue}${txtUnits}%"
        } else {
            "${getString(R.string.battery)} ${txtLabel}: ${txtValue}${txtUnits}"
        }

        val iconUnits = if (indicatorUnits == "%") "" else txtUnits
        
        noteBuilder
            .setContentTitle(title)
            .setSmallIcon(renderIcon(txtValue, iconUnits))

        noteBuilder.setContentText(
            when(val seconds = snapshot.secondsUntilCharged) {
                null -> "Heat: ${fmt(snapshot.celsius)}°C | Power: ${fmt(snapshot.watts)}W"
                0.0 -> "Fully charged! | P: ${fmt(snapshot.watts)}W"
                else -> "${fmtSeconds(seconds)} untilFull| T:${fmt(snapshot.celsius)}°C | P:${fmt(snapshot.watts)}W"
            }
        )

        noteMgr.notify(noteId, noteBuilder.build())

        updateData()
    
    }
    
}
