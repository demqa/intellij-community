// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring

@Suppress("SuspiciousCallableReferenceInLambda")
internal class K1CommonRefactoringSettings : KotlinCommonRefactoringSettingsBase<KotlinRefactoringSettings>() {
    override val instance: KotlinRefactoringSettings
        get() = KotlinRefactoringSettings.instance

    override var RENAME_SEARCH_IN_COMMENTS_FOR_CLASS: Boolean
            by delegateTo { it::RENAME_SEARCH_IN_COMMENTS_FOR_CLASS }

    override var RENAME_SEARCH_FOR_TEXT_FOR_CLASS: Boolean
            by delegateTo { it::RENAME_SEARCH_FOR_TEXT_FOR_CLASS }

    override var RENAME_SEARCH_IN_COMMENTS_FOR_FUNCTION: Boolean
            by delegateTo { it::RENAME_SEARCH_IN_COMMENTS_FOR_METHOD }

    override var RENAME_SEARCH_FOR_TEXT_FOR_FUNCTION: Boolean
            by delegateTo { it::RENAME_SEARCH_FOR_TEXT_FOR_METHOD }

    override var RENAME_SEARCH_IN_COMMENTS_FOR_PARAMETER: Boolean
            by delegateTo { it::RENAME_SEARCH_IN_COMMENTS_FOR_VARIABLE }

    override var RENAME_SEARCH_IN_COMMENTS_FOR_PROPERTY: Boolean
            by delegateTo { it::RENAME_SEARCH_IN_COMMENTS_FOR_FIELD }

    override var RENAME_SEARCH_FOR_TEXT_FOR_PROPERTY: Boolean
            by delegateTo { it::RENAME_SEARCH_FOR_TEXT_FOR_FIELD }

    override var MOVE_PREVIEW_USAGES: Boolean
            by delegateTo { it::MOVE_PREVIEW_USAGES }

    override var MOVE_SEARCH_IN_COMMENTS: Boolean
            by delegateTo { it::MOVE_SEARCH_IN_COMMENTS }

    override var MOVE_SEARCH_FOR_TEXT: Boolean
            by delegateTo { it::MOVE_SEARCH_FOR_TEXT }

    override var MOVE_SEARCH_REFERENCES: Boolean
            by delegateTo { it::MOVE_SEARCH_REFERENCES }

    override var MOVE_DELETE_EMPTY_SOURCE_FILES: Boolean
            by delegateTo { it::MOVE_DELETE_EMPTY_SOURCE_FILES }

    override var MOVE_MPP_DECLARATIONS: Boolean
            by delegateTo { it::MOVE_MPP_DECLARATIONS }

    override var INTRODUCE_DECLARE_WITH_VAR: Boolean
            by delegateTo { it::INTRODUCE_DECLARE_WITH_VAR }

    override var INTRODUCE_SPECIFY_TYPE_EXPLICITLY: Boolean
            by delegateTo { it::INTRODUCE_SPECIFY_TYPE_EXPLICITLY }

    override var renameVariables: Boolean
            by delegateTo { it::renameVariables }

    override var renameParameterInHierarchy: Boolean
            by delegateTo { it::renameParameterInHierarchy }

    override var renameInheritors: Boolean
            by delegateTo { it::renameInheritors }

    override var renameOverloads: Boolean
            by delegateTo { it::renameOverloads }
}