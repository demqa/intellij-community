// "Opt in for 'Library' on statement" "true"
// ACTION: Add '-opt-in=Simple.Library' to module light_idea_test_case compiler arguments
// ACTION: Do not show return expression hints
// ACTION: Introduce local variable
// ACTION: Opt in for 'Library' in containing file 'simple.kts'
// ACTION: Opt in for 'Library' on 'bar'
// ACTION: Opt in for 'Library' on statement
// RUNTIME_WITH_SCRIPT_RUNTIME
@RequiresOptIn
annotation class Library()

@Library
class MockLibrary


@Library
val foo: MockLibrary = MockLibrary();

{
    fun bar() {
        foo<caret>
    }
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.OptInFixesFactory$HighPriorityUseOptInAnnotationFix