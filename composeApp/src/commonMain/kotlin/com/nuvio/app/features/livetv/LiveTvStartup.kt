package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.record.LiveTvRecordingScheduler
import com.nuvio.app.features.livetv.reminders.LiveTvReminders
import com.nuvio.app.features.livetv.sports.SportsReminders
import com.nuvio.app.features.livetv.sports.SportsRepository
import com.nuvio.app.features.livetv.sync.LiveTvCloudSync
import com.nuvio.app.features.livetv.sync.LiveTvDriveSync
import com.nuvio.app.features.livetv.virtual.VirtualChannelRepository

/** Starts Live TV's background work once the preferences are installed: updates and Drive sync. */
internal object LiveTvStartup {
    private var started = false

    fun start() {
        if (started) return
        started = true
        VirtualChannelRepository.ensureLoaded()
        // Do not preload the channel list here. Large playlists (100k+ channels) plus their
        // guide take seconds to parse and would compete with the first Home frame. Live TV,
        // deep links, reminders and recording alarms load the repository on demand.
        LiveTvDriveSync.start()
        LiveTvCloudSync.start()
        LiveTvRecordingScheduler.start()
        LiveTvReminders.start()
        // Sports: scores for followed teams, game reminders and alerts (its own loop stops when Sports is off).
        SportsRepository.start()
        SportsReminders.start()
    }
}
