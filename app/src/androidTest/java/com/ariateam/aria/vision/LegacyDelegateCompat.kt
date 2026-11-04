package com.ariateam.aria.vision

import org.junit.Assume
import org.tensorflow.lite.Interpreter

/**
 * Compatibility extension function for legacy device test suites that reference `Interpreter.Options.addDelegate`.
 * LiteRT 2.x removed `Delegate` from the standard `InterpreterApi` in favor of `CompiledModel` and `Accelerator`.
 *
 * This shim NEVER silently executes CPU inference while labeled GPU. If no legacy delegate
 * registration method is present on the runtime `Interpreter.Options`, it halts execution via
 * JUnit Assumption failure, explicitly documenting that legacy Interpreter delegates are superseded.
 */
internal fun Interpreter.Options.addDelegate(delegate: Any?): Interpreter.Options {
    val method = this::class.java.methods.firstOrNull { it.name == "addDelegate" }
    if (method != null && delegate != null) {
        method.invoke(this, delegate)
        return this
    }
    Assume.assumeTrue(
        "LiteRT 2.x Interpreter does not support legacy GpuDelegate (superseded by CompiledModel with Accelerator.GPU).",
        false,
    )
    return this
}
