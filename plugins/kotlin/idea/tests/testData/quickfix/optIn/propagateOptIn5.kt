// "Propagate 'UnstableApi' opt-in requirement to 'SomeImplementation'" "true"
// IGNORE_FIR
// COMPILER_ARGUMENTS: -opt-in=kotlin.RequiresOptIn
// WITH_STDLIB

@RequiresOptIn
annotation class UnstableApi

@SubclassOptInRequired(UnstableApi::class)
interface CoreLibraryApi

enum class SomeImplementation : CoreLibraryApi<caret>
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.OptInFixesFactory$PropagateOptInAnnotationFix