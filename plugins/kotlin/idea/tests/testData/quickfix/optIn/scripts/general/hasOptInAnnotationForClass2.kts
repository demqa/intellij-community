// "Opt in for 'B' on containing class 'C'" "true"
// ACTION: Add '-opt-in=HasOptInAnnotationForClass2.B' to module light_idea_test_case compiler arguments
// ACTION: Go To Super Method
// ACTION: Opt in for 'B' in containing file 'hasOptInAnnotationForClass2.kts'
// ACTION: Opt in for 'B' on 'bar'
// ACTION: Opt in for 'B' on containing class 'C'
// ACTION: Propagate 'B' opt-in requirement to 'bar'
// ACTION: Propagate 'B' opt-in requirement to containing class 'C'
// RUNTIME_WITH_SCRIPT_RUNTIME
@RequiresOptIn
annotation class A

@RequiresOptIn
annotation class B

interface I {
    @A
    fun foo(): Unit

    @B
    fun bar(): Unit
}

@OptIn(A::class)
class C : I {
    override fun foo() {}
    override fun <caret>bar() {}
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.OptInFixesFactory$UseOptInAnnotationFix