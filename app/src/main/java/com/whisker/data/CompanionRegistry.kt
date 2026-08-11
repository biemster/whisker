package com.whisker.data

import android.content.Context

object CompanionRegistry {
    @Volatile
    var networkManager: GatewayNetworkManager? = null

    @Volatile
    var isAppInForeground: Boolean = false

    fun getOrInitializeManager(context: Context): GatewayNetworkManager {
        val current = networkManager
        if (current != null) return current
        
        return synchronized(this) {
            val doubleCheck = networkManager
            if (doubleCheck != null) {
                doubleCheck
            } else {
                val db = AppDatabase.getDatabase(context.applicationContext)
                val repo = LinkRepository(db)
                val mgr = GatewayNetworkManager(context.applicationContext, repo)
                networkManager = mgr
                mgr
            }
        }
    }
}
