// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.wizards

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.importing.MavenImportUtil
import org.jetbrains.idea.maven.model.MavenExplicitProfiles
import org.jetbrains.idea.maven.navigator.MavenProjectsNavigator
import org.jetbrains.idea.maven.project.*
import org.jetbrains.idea.maven.project.actions.LookForNestedToggleAction
import org.jetbrains.idea.maven.server.MavenServerManager
import org.jetbrains.idea.maven.server.MavenWrapperSupport.Companion.getWrapperDistributionUrl
import org.jetbrains.idea.maven.utils.FileFinder
import org.jetbrains.idea.maven.utils.MavenAsyncUtil
import org.jetbrains.idea.maven.utils.MavenLog
import org.jetbrains.idea.maven.utils.MavenUtil

internal class MavenProjectAsyncBuilder {
  fun commitSync(project: Project, projectFile: VirtualFile, modelsProvider: IdeModifiableModelsProvider?): List<Module> {
    if (ApplicationManager.getApplication().isDispatchThread) {
      return runWithModalProgressBlocking(project, MavenProjectBundle.message("maven.reading")) {
        commit(project, projectFile, modelsProvider)
      }
    }
    else {
      return runBlockingMaybeCancellable {
        commit(project, projectFile, modelsProvider)
      }
    }
  }

  suspend fun commit(project: Project, projectFile: VirtualFile, modelsProvider: IdeModifiableModelsProvider?): List<Module> {
    val importProjectFile = if (!projectFile.isDirectory) projectFile else null
    val rootDirectory = if (projectFile.isDirectory) projectFile.toNioPath() else projectFile.parent.toNioPath()

    val isVeryNewProject = true == project.getUserData(ExternalSystemDataKeys.NEWLY_CREATED_PROJECT)

    val directProjectsSettings = MavenWorkspaceSettingsComponent.getInstance(project).settings

    val importingSettings = directProjectsSettings.getImportingSettings().clone()

    if (isVeryNewProject) {
      blockingContext { ExternalProjectsManagerImpl.setupCreatedProject(project) }
      MavenProjectsManager.setupCreatedMavenProject(importingSettings)
    }
    if (ApplicationManager.getApplication().isDispatchThread) {
      FileDocumentManager.getInstance().saveAllDocuments()
    }
    MavenAsyncUtil.setupProjectSdk(project)
    val projectsNavigator = MavenProjectsNavigator.getInstance(project)
    if (projectsNavigator != null) projectsNavigator.groupModules = true

    val files: List<VirtualFile?> = withBackgroundProgress(project, MavenProjectBundle.message("maven.reading"), true) {
      withRawProgressReporter {
        coroutineToIndicator {
          val indicator = ProgressManager.getGlobalProgressIndicator()
          if (importProjectFile != null) {
            return@coroutineToIndicator listOf(importProjectFile)
          }
          val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
            FileUtil.toSystemIndependentName(rootDirectory.toString()))
          if (virtualFile == null) {
            return@coroutineToIndicator emptyList()
          }
          return@coroutineToIndicator FileFinder.findPomFiles(virtualFile.children, LookForNestedToggleAction.isSelected(), indicator)
        }
      }
    }

    val tree = MavenProjectsTree(project)
    tree.addManagedFilesWithProfiles(files, MavenExplicitProfiles.NONE)

    val generalSettings = directProjectsSettings.getGeneralSettings().clone()
    generalSettings.updateFromMavenConfig(files)

    withBackgroundProgress(project, MavenProjectBundle.message("maven.reading"), false) {
      withRawProgressReporter {
        coroutineToIndicator {
          val indicator = ProgressManager.getGlobalProgressIndicator()
          tree.updateAll(false, generalSettings, indicator)
        }
      }
    }

    val projects = tree.rootProjects

    if (projects.isEmpty()) {
      LOG.warn(String.format("Cannot import project for %s", project.toString()))
      return emptyList()
    }

    val settings = MavenWorkspaceSettingsComponent.getInstance(project).settings
    settings.setGeneralSettings(generalSettings)
    settings.setImportingSettings(importingSettings)
    val settingsFile = System.getProperty("idea.maven.import.settings.file")
    if (!settingsFile.isNullOrBlank()) {
      settings.getGeneralSettings().setUserSettingsFile(settingsFile.trim { it <= ' ' })
    }
    val distributionUrl = getWrapperDistributionUrl(project.guessProjectDir())
    if (distributionUrl != null) {
      settings.getGeneralSettings().mavenHome = MavenServerManager.WRAPPED_MAVEN
    }
    val selectedProfiles = MavenExplicitProfiles.NONE.clone()
    val enabledProfilesList = System.getProperty("idea.maven.import.enabled.profiles")
    val disabledProfilesList = System.getProperty("idea.maven.import.disabled.profiles")
    if (enabledProfilesList != null || disabledProfilesList != null) {
      appendProfilesFromString(selectedProfiles.enabledProfiles, enabledProfilesList)
      appendProfilesFromString(selectedProfiles.disabledProfiles, disabledProfilesList)
    }
    val manager = MavenProjectsManager.getInstance(project)
    manager.setIgnoredState(projects, false)
    MavenLog.LOG.warn("performImport async: Linear Import is disabled")

    if (isVeryNewProject && Registry.`is`("maven.create.dummy.module.on.first.import")) {
      // do not update all modules because it can take a lot of time (freeze at project opening)
      val previewModule = createPreviewModule(project, projects)
      blockingContext { manager.addManagedFilesWithProfiles(MavenUtil.collectFiles(projects), selectedProfiles, previewModule) }
      return if (null == previewModule) emptyList() else listOf(previewModule)
    }

    return manager.addManagedFilesWithProfilesAndUpdate(MavenUtil.collectFiles(projects), selectedProfiles, modelsProvider)
  }

  private suspend fun createPreviewModule(project: Project, selectedProjects: List<MavenProject>): Module? {
    if (ModuleManager.getInstance(project).modules.isEmpty()) {
      val root = selectedProjects.firstOrNull() ?: return null
      val contentRoot = root.directoryFile
      return withContext(Dispatchers.EDT) { MavenImportUtil.createPreviewModule(project, contentRoot) }
    }
    return null
  }

  companion object {
    private val LOG = Logger.getInstance(MavenProjectAsyncBuilder::class.java)

    private fun appendProfilesFromString(selectedProfiles: MutableCollection<String>, profilesList: String?) {
      if (profilesList == null) return
      for (profile in StringUtil.split(profilesList, ",")) {
        val trimmedProfileName = profile.trim { it <= ' ' }
        if (!trimmedProfileName.isEmpty()) {
          selectedProfiles.add(trimmedProfileName)
        }
      }
    }
  }
}
