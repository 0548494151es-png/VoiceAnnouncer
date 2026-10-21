package com.example.voiceannouncer

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // רץ בכל הפעלה של התהליך (גם כשהמערכת מעירה את השירות או את מקלט השיחות)
        LogStore.init(this)
        LogStore.add("התהליך הופעל")
        Speaker.init(this)
    }
}
