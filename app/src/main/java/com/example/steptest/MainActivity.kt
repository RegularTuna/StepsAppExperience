package com.example.steptest

import android.content.Intent

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Duration

import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {



    // Create a set of permissions for required data types
    val PERMISSIONS =
        setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            "android.permission.health.READ_HEALTH_DATA_HISTORY" //this allows us to see the 30 day recrds but app store will reject the app if we don't explain why
        )
    val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

    val requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
        if (granted.containsAll(PERMISSIONS)) {
            // Success! The user said yes.
            println("Permissions Granted!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }



        val providerPackageName = "com.google.android.apps.healthdata"

        val availabilityStatus = HealthConnectClient.getSdkStatus(this, providerPackageName)
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return // early return as there is no viable integration
        }
        val healthConnectClient = HealthConnectClient.getOrCreate(this)

        // THIS IS THE NEW PART: Trigger the permission check
        lifecycleScope.launch {
            checkPermissionsAndRun(healthConnectClient)
        }


        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            // Optionally redirect to package installer to find a provider, for example:
            val uriString = "market://details?id=$providerPackageName&url=healthconnect%3A%2F%2Fonboarding"
            this.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    putExtra("overlay", true)
                    putExtra("callerId", packageName)
                }
            )
            return
        }

        // Issue operations with healthConnectClient
        val btnWriteSteps = findViewById<Button>(R.id.btnWriteSteps)

        btnWriteSteps.setOnClickListener {
            lifecycleScope.launch {
                insertSteps(healthConnectClient)
            }
        }

        val btnReadSteps = findViewById<Button>(R.id.btnReadSteps)
        btnReadSteps.setOnClickListener {
            lifecycleScope.launch {
                readTodaySteps(healthConnectClient)
            }
        }

        //table logic
        // Call it immediately on open
        lifecycleScope.launch {
            updateStepsTable(healthConnectClient)
        }

        // Also link it to the refresh button
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            lifecycleScope.launch {
                updateStepsTable(healthConnectClient)
            }
        }
    }
    // This function checks if we have permissions. If not, it opens the popup.
    private suspend fun checkPermissionsAndRun(healthConnectClient: HealthConnectClient) {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(PERMISSIONS)) {
            // Already have permissions! You can now read data.
            println("We already have permissions.")
        } else {
            // This is what actually makes the screen pop up
            requestPermissions.launch(PERMISSIONS)
        }
    }

    //write steps data manually
    suspend fun insertSteps(healthConnectClient: HealthConnectClient) {
        val currentOffset = java.time.ZonedDateTime.now().offset
        val endTime = Instant.now().minus(Duration.ofHours(4))
        val startTime = endTime.minus(Duration.ofMinutes(15))
        try {
            val stepsRecord = StepsRecord(
                count = 120,
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = currentOffset,
                endZoneOffset = currentOffset,
                metadata = Metadata.autoRecorded(
                    device = Device(type = Device.TYPE_WATCH)
                ), // Pass an empty Metadata object
            )
            healthConnectClient.insertRecords(listOf(stepsRecord))
            // Toast helps you see it actually worked!
            android.widget.Toast.makeText(this, "Steps added!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //read steps


    suspend fun readTodaySteps(healthConnectClient: HealthConnectClient) {
        // 1. Set the time range (Start of today until now)
        val startTime = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val endTime = Instant.now()

        try {
            // 2. Ask for the AGGREGATED total
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )

            // 3. Get the number from the result
            val stepCount = response[StepsRecord.COUNT_TOTAL] ?: 0

            // 4. Show it on the screen
            android.widget.Toast.makeText(this, "Total Steps Today: $stepCount", android.widget.Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Error reading steps", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    //read steps to the table
    suspend fun updateStepsTable(healthConnectClient: HealthConnectClient) {
        val tvDay = findViewById<TextView>(R.id.tvSteps24h)
        val tvWeek = findViewById<TextView>(R.id.tvStepsLastWeek)

        val zoneId = ZoneId.systemDefault()
        val now = Instant.now()


        val sevenDaysAgo = LocalDate.now(zoneId).minusDays(7).atStartOfDay(zoneId).toInstant()
        val startOfToday = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
        try {
            val responseWeek = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo,now)

                )
            )

            val responseDay = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfToday,now)
                )
            )
            println("$responseWeek \n\n\n\n\n\n")

            val stepCountDay = responseDay[StepsRecord.COUNT_TOTAL] ?: 0L
            val stepCountWeek = responseWeek[StepsRecord.COUNT_TOTAL] ?: 0L


            runOnUiThread {
                tvWeek.text = stepCountWeek.toString()
                tvDay.text = stepCountDay.toString()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }




}