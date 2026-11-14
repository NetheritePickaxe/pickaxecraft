package org.pickaxe.androidhost;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * MethodHandle 探针。
 *
 * <p>用 Java 而不是 Kotlin 写，是因为 minSdk 26 的下限来自
 * {@code MethodHandle.invokeExact} 这类签名多态调用：Kotlin 源码里很难保证
 * 编译器真的发出 invokeExact 指令，写在 Java 里则一目了然。</p>
 *
 * <p>在 ART 上调用失败会抛 {@link WrongMethodTypeException} 或
 * {@link UnsupportedOperationException}，由宿主捕获后写进报告。</p>
 */
public final class MethodHandleProbe {

    private MethodHandleProbe() {
    }

    /** 被反射调用的目标：加法，便于用返回值判断调用真的发生了。 */
    public static int add(int a, int b) {
        return a + b;
    }

    /**
     * 走一次严格的 {@code invokeExact}。
     *
     * @return 2 + 3 = 5；任何一步失败都会以异常形式抛给调用方
     */
    public static int invokeExactAdd(int a, int b) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodType type = MethodType.methodType(int.class, int.class, int.class);
        MethodHandle handle = lookup.findStatic(MethodHandleProbe.class, "add", type);
        return (int) handle.invokeExact(a, b);
    }
}
