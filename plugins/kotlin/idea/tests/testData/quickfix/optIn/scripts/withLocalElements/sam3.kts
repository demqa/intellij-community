// "Opt in for 'MyOptIn' on 'SamI2'" "true"
// RUNTIME_WITH_SCRIPT_RUNTIME
// ACTION: Add '-opt-in=Sam3.MyOptIn' to module light_idea_test_case compiler arguments
// ACTION: Do not show implicit receiver and parameter hints
// ACTION: Opt in for 'MyOptIn' in containing file 'sam3.kts'
// ACTION: Opt in for 'MyOptIn' on 'SamI'
// ACTION: Opt in for 'MyOptIn' on 'SamI2'
// ACTION: Opt in for 'MyOptIn' on statement

@RequiresOptIn
annotation class MyOptIn

@MyOptIn
fun foo() {
}

fun interface SamI {
    fun run()
}

fun interface SamI2 {
    fun run()
}

SamI2 {
    SamI {
        foo<caret>()
    }
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.OptInFixesFactory$HighPriorityUseOptInAnnotationFix