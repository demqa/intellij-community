// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.gradleCodeInsightCommon

import com.intellij.codeInsight.CodeInsightUtilCore
import com.intellij.codeInsight.daemon.impl.quickfix.OrderEntryFix
import com.intellij.ide.actions.OpenFileAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectNotificationAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExternalLibraryDescriptor
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.gradle.util.GradleVersion
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.base.projectStructure.ModuleSourceRootGroup
import org.jetbrains.kotlin.idea.base.projectStructure.toModuleGroup
import org.jetbrains.kotlin.idea.compiler.configuration.IdeKotlinVersion
import org.jetbrains.kotlin.idea.configuration.*
import org.jetbrains.kotlin.idea.extensions.gradle.KotlinGradleConstants.GRADLE_PLUGIN_ID
import org.jetbrains.kotlin.idea.extensions.gradle.KotlinGradleConstants.GROUP_ID
import org.jetbrains.kotlin.idea.facet.getRuntimeLibraryVersion
import org.jetbrains.kotlin.idea.facet.getRuntimeLibraryVersionOrDefault
import org.jetbrains.kotlin.idea.framework.ui.ConfigureDialogWithModulesAndVersion
import org.jetbrains.kotlin.idea.gradle.KotlinIdeaGradleBundle
import org.jetbrains.kotlin.idea.projectConfiguration.LibraryJarDescriptor
import org.jetbrains.kotlin.idea.projectConfiguration.getJvmStdlibArtifactId
import org.jetbrains.kotlin.idea.quickfix.AbstractChangeFeatureSupportLevelFix
import org.jetbrains.kotlin.idea.statistics.KotlinJ2KOnboardingFUSCollector
import org.jetbrains.kotlin.idea.util.application.executeCommand
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.tools.projectWizard.compatibility.KotlinGradleCompatibilityStore
import org.jetbrains.plugins.gradle.settings.GradleSettings

abstract class KotlinWithGradleConfigurator : KotlinProjectConfigurator {

    override fun getStatus(moduleSourceRootGroup: ModuleSourceRootGroup): ConfigureKotlinStatus {
        val module = moduleSourceRootGroup.baseModule
        if (!isApplicable(module)) {
            return ConfigureKotlinStatus.NON_APPLICABLE
        }

        if (moduleSourceRootGroup.sourceRootModules.all(Module::hasKotlinPluginEnabled)) {
            return ConfigureKotlinStatus.CONFIGURED
        }

        val (projectBuildFile, topLevelBuildFile) = runReadAction {
            module.getBuildScriptPsiFile() to module.project.getTopLevelBuildScriptPsiFile()
        }

        if (projectBuildFile == null && topLevelBuildFile == null) {
            return ConfigureKotlinStatus.NON_APPLICABLE
        }

        if (projectBuildFile?.isConfiguredByAnyGradleConfigurator() == true) {
            return ConfigureKotlinStatus.BROKEN
        }

        return ConfigureKotlinStatus.CAN_BE_CONFIGURED
    }

    private fun PsiFile.isConfiguredByAnyGradleConfigurator(): Boolean {
        @Suppress("DEPRECATION")
        val extensions = Extensions.getExtensions(KotlinProjectConfigurator.EP_NAME)

        return extensions
            .filterIsInstance<KotlinWithGradleConfigurator>()
            .any { it.isFileConfigured(this) }
    }

    override fun isApplicable(module: Module): Boolean =
        module.buildSystemType == BuildSystemType.Gradle

    protected open fun getMinimumSupportedVersion() = "1.0.0"

    protected fun PsiFile.isKtDsl() = this is KtFile

    private fun isFileConfigured(buildScript: PsiFile): Boolean {
        val manipulator = GradleBuildScriptSupport.findManipulator(buildScript) ?: return false
        return with(manipulator) {
            isConfiguredWithOldSyntax(kotlinPluginName) || isConfigured(getKotlinPluginExpression(buildScript.isKtDsl()))
        }
    }

    @JvmSuppressWildcards
    override fun configure(project: Project, excludeModules: Collection<Module>) {
        val dialog = ConfigureDialogWithModulesAndVersion(project, this, excludeModules, getMinimumSupportedVersion())

        dialog.show()
        if (!dialog.isOK) return

        KotlinJ2KOnboardingFUSCollector.logStartConfigureKt(project)
        val result = configureSilently(
            project,
            dialog.modulesToConfigure,
            dialog.versionsAndModules,
            IdeKotlinVersion.get(dialog.kotlinVersion),
            dialog.modulesAndJvmTargets
        )

        for (file in result.changedFiles.getChangedFiles()) {
            OpenFileAction.openFile(file.virtualFile, project)
        }

        result.collector.showNotification()
    }

    private fun getAllConfigurableKotlinVersions(): List<IdeKotlinVersion> {
        return KotlinGradleCompatibilityStore.allKotlinVersions()
    }

    /**
     * Currently, returns true if this module has a jvmTarget >= 8.
     * If a future Kotlin version requires a higher jvmTarget, then it will be required for that [kotlinVersion].
     */
    private fun Module.kotlinSupportsJvmTarget(kotlinVersion: IdeKotlinVersion): Boolean {
        val jvmTarget = getTargetBytecodeVersionFromModule(this, kotlinVersion) ?: return false
        val jvmTargetNum = getJvmTargetNumber(jvmTarget) ?: return false
        return jvmTargetNum >= 8
    }

    private fun Project.isGradleSyncPending(): Boolean {
        val notificationVisibleProperty =
            ExternalSystemProjectNotificationAware.isNotificationVisibleProperty(this, ProjectSystemId("GRADLE", "Gradle"))
        return notificationVisibleProperty.get()
    }

    override fun calculateAutoConfigSettings(module: Module): AutoConfigurationSettings? {
        val project = module.project
        val baseModule = module.toModuleGroup().baseModule

        if (!isAutoConfigurationEnabled() || !isApplicable(baseModule)) return null
        if (project.isGradleSyncPending() || project.isGradleSyncInProgress()) return null
        if (module.hasKotlinPluginEnabled() || baseModule.getBuildScriptPsiFile() == null) return null

        val gradleVersion = project.guessProjectDir()?.path?.let {
            val linkedSettings = GradleSettings.getInstance(project).getLinkedProjectSettings(it)
            linkedSettings?.resolveGradleVersion()
        } ?: return null

        val hierarchy = project.buildKotlinModuleHierarchy() ?: return null
        val moduleNode = hierarchy.getNodeForModule(baseModule) ?: return null
        if (moduleNode.definedKotlinVersion != null || moduleNode.hasKotlinVersionConflict()) return null

        val forcedKotlinVersion = moduleNode.getForcedKotlinVersion()
        val allConfigurableKotlinVersions = getAllConfigurableKotlinVersions()
        if (forcedKotlinVersion != null && !allConfigurableKotlinVersions.contains(forcedKotlinVersion)) {
            return null
        }

        val possibleKotlinVersionsToUse = if (forcedKotlinVersion != null) {
            listOf(forcedKotlinVersion)
        } else allConfigurableKotlinVersions

        val remainingKotlinVersions = possibleKotlinVersionsToUse.filter {
            baseModule.kotlinSupportsJvmTarget(it)
        }.filter {
            KotlinGradleCompatibilityStore.kotlinVersionSupportsGradle(it, gradleVersion)
        }

        val maxKotlinVersion = remainingKotlinVersions.maxOrNull() ?: return null

        return AutoConfigurationSettings(baseModule, maxKotlinVersion)
    }

    private fun Project.scheduleGradleSync() {
        ExternalSystemProjectTracker.getInstance(this).scheduleProjectRefresh()
    }

    override fun runAutoConfig(settings: AutoConfigurationSettings) {
        val module = settings.module
        val project = module.project
        val moduleVersions = getKotlinVersionsAndModules(project, this).first
        val jvmTargets = getModulesTargetingUnsupportedJvmAndTargetsForAllModules(listOf(module), settings.kotlinVersion).second

        val result = configureSilently(
            module.project,
            listOf(module),
            moduleVersions,
            settings.kotlinVersion,
            jvmTargets,
            "command.name.configure.kotlin.automatically",
            isAutoConfig = true
        )

        KotlinAutoConfigurationNotificationHolder.getInstance()
            .showAutoConfiguredNotification(project, module.name, result.changedFiles.calculateChanges())
    }

    private class ConfigurationResult(
        val collector: NotificationMessageCollector,
        val changedFiles: ChangedConfiguratorFiles
    )

    private fun configureSilently(
        project: Project,
        modules: List<Module>,
        kotlinVersionsAndModules: Map<String, Map<String, Module>>,
        version: IdeKotlinVersion,
        modulesAndJvmTargets: Map<ModuleName, TargetJvm>,
        commandKey: String = "command.name.configure.kotlin",
        isAutoConfig: Boolean = false
    ): ConfigurationResult {
        return project.executeCommand(KotlinIdeaGradleBundle.message(commandKey)) {
            val collector = NotificationMessageCollector.create(project)
            val changedFiles = configureWithVersion(project, modules, version, collector, kotlinVersionsAndModules, modulesAndJvmTargets)

            if (isAutoConfig) {
                project.scheduleGradleSync()
                val module = modules.firstOrNull() // Auto-configuration only has a single module
                UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction() {
                    override fun undo() {
                        project.scheduleGradleSync()
                        KotlinAutoConfigurationNotificationHolder.getInstance().showAutoConfigurationUndoneNotification(project, module)
                    }

                    override fun redo() {
                        project.scheduleGradleSync()
                        KotlinAutoConfigurationNotificationHolder.getInstance().reshowAutoConfiguredNotification(project, module)
                    }
                })
            }

            ConfigurationResult(collector, changedFiles)
        }
    }

    fun configureWithVersion(
        project: Project,
        modulesToConfigure: List<Module>,
        kotlinVersion: IdeKotlinVersion,
        collector: NotificationMessageCollector,
        kotlinVersionsAndModules: Map<String, Map<String, Module>>,
        modulesAndJvmTargets: Map<ModuleName, TargetJvm> = emptyMap()
    ): ChangedConfiguratorFiles {
        val changedFiles = ChangedConfiguratorFiles()
        val topLevelBuildScript = project.getTopLevelBuildScriptPsiFile()
        var addVersionToModuleBuildScript = true
        val modulesWithTheSameKotlin = kotlinVersionsAndModules[kotlinVersion.artifactVersion]
        val modulesToRemoveKotlinVersion = mutableListOf<Module>()
        // Remove version from modules with the same version as the version to configure:
        modulesWithTheSameKotlin?.values?.let { modulesToRemoveKotlinVersion.addAll(it) }
        val rootModule = getRootModule(project)
        if (rootModule != null) {
            val addVersionToSettings: Boolean
            // If there are different Kotlin versions in the project, don't add to settings
            if (kotlinVersionsAndModules.filter { it.key != kotlinVersion.artifactVersion }.isNotEmpty()) {
                addVersionToSettings = false
            } else {
                // If we have any version in the root module, don't need to add the version to the settings file
                addVersionToSettings = !kotlinVersionsAndModules.values.flatMap { it.keys }.contains(rootModule.name)
            }
            if (addVersionToSettings) {
                rootModule.getBuildScriptSettingsPsiFile()?.let {
                    if (it.canBeConfigured() && configureSettingsFile(
                            it,
                            kotlinVersion,
                            changedFiles
                        )
                    ) { // This happens only for JVM, not for Android
                        addVersionToModuleBuildScript = false
                    }
                }
            }
            if (topLevelBuildScript != null) {
                // rootModule is just <PROJECT_NAME>, but we need <PROJECT_NAME>.main:
                val rootModuleName = rootModule.name
                val firstSourceRootModule = modulesWithTheSameKotlin?.get(rootModuleName)
                firstSourceRootModule?.let {
                    // We don't cut a Kotlin version from a top build script
                    modulesToRemoveKotlinVersion.remove(firstSourceRootModule)
                    addVersionToModuleBuildScript = false
                }

                val jvmTarget = if (modulesAndJvmTargets.isNotEmpty()) {
                    modulesAndJvmTargets[rootModuleName]
                } else {
                    getTargetBytecodeVersionFromModule(rootModule, kotlinVersion)
                }
                if (topLevelBuildScript.canBeConfigured()) {
                    configureModule(
                        rootModule,
                        topLevelBuildScript,
                        /* isTopLevelProjectFile = true is needed only for KotlinAndroidGradleModuleConfigurator that overrides
                addElementsToFiles()*/
                        isTopLevelProjectFile = true,
                        kotlinVersion,
                        jvmTarget,
                        collector,
                        changedFiles,
                        addVersionToModuleBuildScript
                    )

                    if (modulesToConfigure.contains(rootModule)) {
                        configureModule(
                            rootModule,
                            topLevelBuildScript,
                            false,
                            kotlinVersion,
                            jvmTarget,
                            collector,
                            changedFiles,
                            addVersionToModuleBuildScript
                        )
                        // If Kotlin version wasn't added to settings.gradle, then it has just been added to root script
                        addVersionToModuleBuildScript = false
                    }
                } else {
                    showErrorMessage(
                        project,
                        KotlinIdeaGradleBundle.message("error.text.cannot.find.build.gradle.file.for.module", rootModule.name)
                    )
                    return changedFiles
                }
            }
        }

        for (module in modulesToRemoveKotlinVersion) {
            module.getBuildScriptPsiFile()?.let {
                removeKotlinVersionFromBuildScript(it, changedFiles)
            }
        }

        for (module in modulesToConfigure) {
            val file = module.getBuildScriptPsiFile()
            if (file != null && file.canBeConfigured()) {
                if (file == topLevelBuildScript) { // We configured the root module separately above
                    continue
                }
                val jvmTarget = if (modulesAndJvmTargets.isNotEmpty()) {
                    modulesAndJvmTargets[module.name]
                } else {
                    getTargetBytecodeVersionFromModule(module, kotlinVersion)
                }
                configureModule(module, file, false, kotlinVersion, jvmTarget, collector, changedFiles, addVersionToModuleBuildScript)
            } else {
                showErrorMessage(
                    project,
                    KotlinIdeaGradleBundle.message("error.text.cannot.find.build.gradle.file.for.module", module.name)
                )
                return changedFiles
            }
        }
        for (file in changedFiles.getChangedFiles()) {
            file.virtualFile?.let {
                collector.addMessage(KotlinIdeaGradleBundle.message("text.was.modified", it.path))
            }
        }
        return changedFiles
    }

    private fun removeKotlinVersionFromBuildScript(
        file: PsiFile,
        changedFiles: ChangedConfiguratorFiles
    ) {
        file.project.executeWriteCommand(KotlinIdeaGradleBundle.message("command.name.configure.0", file.name), null) {
            changedFiles.storeOriginalFileContent(file)
            GradleBuildScriptSupport.getManipulator(file).findAndRemoveKotlinVersionFromBuildScript()
            CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(file)
        }
    }

    open fun configureModule(
        module: Module,
        file: PsiFile,
        isTopLevelProjectFile: Boolean,
        ideKotlinVersion: IdeKotlinVersion,
        jvmTarget: String?,
        collector: NotificationMessageCollector,
        changedFiles: ChangedConfiguratorFiles,
        addVersion: Boolean = true
    ) {
        configureBuildScripts(file, isTopLevelProjectFile, ideKotlinVersion, jvmTarget, changedFiles, addVersion)
    }

    private fun configureBuildScripts(
        file: PsiFile,
        addVersion: Boolean,
        version: IdeKotlinVersion,
        jvmTarget: String?,
        changedFiles: ChangedConfiguratorFiles
    ) {
        val sdk = ModuleUtil.findModuleForPsiElement(file)?.let { ModuleRootManager.getInstance(it).sdk }
        GradleBuildScriptSupport.getManipulator(file).configureBuildScripts(
            kotlinPluginName,
            getKotlinPluginExpression(file.isKtDsl()),
            getStdlibArtifactName(sdk, version),
            addVersion,
            version,
            jvmTarget,
            changedFiles
        )
    }

    protected open fun getStdlibArtifactName(sdk: Sdk?, version: IdeKotlinVersion) = getJvmStdlibArtifactId(sdk, version)

    protected open fun getJvmTarget(sdk: Sdk?, version: IdeKotlinVersion): String? = null

    protected abstract val kotlinPluginName: String
    protected abstract fun getKotlinPluginExpression(forKotlinDsl: Boolean): String

    protected open fun addElementsToFiles(
        file: PsiFile,
        isTopLevelProjectFile: Boolean,
        version: IdeKotlinVersion,
        jvmTarget: String?,
        addVersion: Boolean = true,
        changedBuildFiles: ChangedConfiguratorFiles
    ) {
        if (!isTopLevelProjectFile) { // isTopLevelProjectFile = true is needed only for Android
            changedBuildFiles.storeOriginalFileContent(file)
            GradleBuildScriptSupport.getManipulator(file).configureProjectBuildScript(kotlinPluginName, version)
            configureBuildScripts(file, addVersion, version, jvmTarget, changedBuildFiles)
        }
    }

    private fun configureBuildScripts(
        file: PsiFile,
        isTopLevelProjectFile: Boolean,
        version: IdeKotlinVersion,
        jvmTarget: String?,
        changedFiles: ChangedConfiguratorFiles,
        addVersion: Boolean = true
    ) {
        file.project.executeWriteCommand(KotlinIdeaGradleBundle.message("command.name.configure.0", file.name), null) {
            addElementsToFiles(file, isTopLevelProjectFile, version, jvmTarget, addVersion, changedFiles)

            for (changedFile in changedFiles.getChangedFiles()) {
                CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(changedFile)
            }
        }
    }

    protected open fun configureSettingsFile(
        file: PsiFile,
        version: IdeKotlinVersion,
        changedFiles: ChangedConfiguratorFiles
    ): Boolean {
        return file.project.executeWriteCommand(KotlinIdeaGradleBundle.message("command.name.configure.0", file.name), null) {
            changedFiles.storeOriginalFileContent(file)
            val isModified = GradleBuildScriptSupport.getManipulator(file)
                .configureSettingsFile(getKotlinPluginExpression(file.isKtDsl()), version)

            CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(file)
            isModified
        }
    }

    override fun updateLanguageVersion(
        module: Module,
        languageVersion: String?,
        apiVersion: String?,
        requiredStdlibVersion: ApiVersion,
        forTests: Boolean
    ) {
        val runtimeUpdateRequired = getRuntimeLibraryVersion(module)?.apiVersion?.let { runtimeVersion ->
            runtimeVersion < requiredStdlibVersion
        } ?: false

        if (runtimeUpdateRequired) {
            Messages.showErrorDialog(
                module.project,
                KotlinIdeaGradleBundle.message("error.text.this.language.feature.requires.version", requiredStdlibVersion),
                KotlinIdeaGradleBundle.message("title.update.language.version")
            )
            return
        }

        val element = changeLanguageVersion(module, languageVersion, apiVersion, forTests)

        element?.let {
            OpenFileDescriptor(module.project, it.containingFile.virtualFile, it.textRange.startOffset).navigate(true)
        }
    }

    override fun changeGeneralFeatureConfiguration(
        module: Module,
        feature: LanguageFeature,
        state: LanguageFeature.State,
        forTests: Boolean
    ) {
        val sinceVersion = feature.sinceApiVersion

        if (state != LanguageFeature.State.DISABLED && getRuntimeLibraryVersionOrDefault(module).apiVersion < sinceVersion) {
            Messages.showErrorDialog(
                module.project,
                KotlinIdeaGradleBundle.message("error.text.support.requires.version", feature.presentableName, sinceVersion),
                AbstractChangeFeatureSupportLevelFix.getFixText(state, feature.presentableName)
            )
            return
        }

        val element = changeFeatureConfiguration(module, feature, state, forTests)
        if (element != null) {
            OpenFileDescriptor(module.project, element.containingFile.virtualFile, element.textRange.startOffset).navigate(true)
        }
    }

    override fun addLibraryDependency(
        module: Module,
        element: PsiElement,
        library: ExternalLibraryDescriptor,
        libraryJarDescriptor: LibraryJarDescriptor,
        scope: DependencyScope
    ) {
        val scope = OrderEntryFix.suggestScopeByLocation(module, element)
        addKotlinLibraryToModule(module, scope, library)
    }

    companion object {
        @NonNls
        const val CLASSPATH = "classpath \"$GROUP_ID:$GRADLE_PLUGIN_ID:\$kotlin_version\""

        fun getGroovyDependencySnippet(
            artifactName: String,
            scope: String,
            withVersion: Boolean,
            gradleVersion: GradleVersion
        ): String {
            val updatedScope = gradleVersion.scope(scope)
            val versionStr = if (withVersion) ":\$kotlin_version" else ""

            return "$updatedScope \"org.jetbrains.kotlin:$artifactName$versionStr\""
        }

        fun getGroovyApplyPluginDirective(pluginName: String) = "apply plugin: '$pluginName'"

        fun addKotlinLibraryToModule(module: Module, scope: DependencyScope, libraryDescriptor: ExternalLibraryDescriptor) {
            val buildScript = module.getBuildScriptPsiFile() ?: return
            if (!buildScript.canBeConfigured()) {
                return
            }

            GradleBuildScriptSupport.getManipulator(buildScript).addKotlinLibraryToModuleBuildScript(module, scope, libraryDescriptor)

            buildScript.virtualFile?.let {
                NotificationMessageCollector.create(buildScript.project)
                    .addMessage(KotlinIdeaGradleBundle.message("text.was.modified", it.path))
                    .showNotification()
            }
        }

        fun changeFeatureConfiguration(
            module: Module,
            feature: LanguageFeature,
            state: LanguageFeature.State,
            forTests: Boolean
        ) = changeBuildGradle(module) {
            GradleBuildScriptSupport.getManipulator(it).changeLanguageFeatureConfiguration(feature, state, forTests)
        }

        fun changeLanguageVersion(module: Module, languageVersion: String?, apiVersion: String?, forTests: Boolean) =
            changeBuildGradle(module) { buildScriptFile ->
                val manipulator = GradleBuildScriptSupport.getManipulator(buildScriptFile)
                var result: PsiElement? = null
                if (languageVersion != null) {
                    result = manipulator.changeLanguageVersion(languageVersion, forTests)
                }

                if (apiVersion != null) {
                    result = manipulator.changeApiVersion(apiVersion, forTests)
                }

                result
            }

        private fun changeBuildGradle(module: Module, body: (PsiFile) -> PsiElement?): PsiElement? = module.getBuildScriptPsiFile()
            ?.takeIf { it.canBeConfigured() }
            ?.let {
                it.project.executeWriteCommand(KotlinIdeaGradleBundle.message("change.build.gradle.configuration"), null) { body(it) }
            }

        fun getKotlinStdlibVersion(module: Module): String? = module.getBuildScriptPsiFile()?.let {
            GradleBuildScriptSupport.getManipulator(it).getKotlinStdlibVersion()
        }

        private fun showErrorMessage(project: Project, @Nls message: String?) {
            Messages.showErrorDialog(
                project,
                "<html>" + KotlinIdeaGradleBundle.message("text.couldn.t.configure.kotlin.gradle.plugin.automatically") + "<br/>" +
                        (if (message != null) "$message<br/>" else "") +
                        "<br/>${KotlinIdeaGradleBundle.message("text.see.manual.installation.instructions")}</html>",
                KotlinIdeaGradleBundle.message("title.configure.kotlin.gradle.plugin")
            )
        }

        fun isAutoConfigurationEnabled(): Boolean {
            return Registry.`is`("kotlin.configuration.gradle.autoConfig.enabled", false)
        }
    }
}
