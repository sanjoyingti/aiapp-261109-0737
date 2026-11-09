package com.aiapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.Project
import com.aiapp.builder.TimelineItem

/** Thin wrapper: the build itself runs in [BuildService] so it survives closing the app. */
class BuildViewModel(private val app: Application) : AndroidViewModel(app) {
    init {
        BuildRunner.load(app)
    }

    val timeline: List<TimelineItem> get() = BuildRunner.timeline
    val running: Boolean get() = BuildRunner.running
    val project: Project? get() = BuildRunner.project

    fun start(request: BuildRequest) = BuildService.start(app, request)

    fun stop() = BuildService.stop(app)

    fun newProject() = BuildRunner.newProject(app)

    fun projects() = BuildRunner.list(app)

    fun openProject(id: String) = BuildRunner.open(app, id)

    fun deleteProject(id: String) = BuildRunner.delete(app, id)
}
