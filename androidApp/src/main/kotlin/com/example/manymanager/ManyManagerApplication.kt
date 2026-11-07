package com.example.simple.manymanager

import android.app.Application
import android.util.Log
import com.example.simple.manymanager.data.ManyManagerRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import com.example.simple.manymanager.data.local.CalculatorEntity
import com.example.simple.manymanager.data.local.ManyManagerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Owns the single persistent repository for this process. */
open class ManyManagerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    open val database: ManyManagerDatabase by lazy { ManyManagerDatabase.create(this) }

    open val repository: ManyManagerRepository by lazy {
        ManyManagerRepository(database, databaseInitialization, object : Clock() {
            // Resolve the device zone at read time, including after a timezone change.
            override fun getZone(): ZoneId = ZoneId.systemDefault()
            override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
            override fun instant(): Instant = Instant.now()
        })
    }

    lateinit var databaseInitialization: Deferred<Result<CalculatorEntity>>
        private set

    override fun onCreate() {
        super.onCreate()
        databaseInitialization = applicationScope.async {
            try {
                Result.success(database.dao().initializeCalculators())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Preserve the failure for future consumers, without interrupting unrelated screens.
                Log.e("ManyManagerDatabase", "Local calculator initialization failed", failure)
                Result.failure(failure)
            }
        }
    }
}
